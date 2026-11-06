# LedgerLine — REST API Specification

## 1. Overview & Protocol Standards

- **Base URL**: `/api`
- **Content-Type**: `application/json`
- **Authentication**: Stateless Bearer Token passed via HTTP Header:
  ```http
  Authorization: Bearer <jwt-token>
  ```
- **Idempotency**: All mutating financial operations (`POST /api/deposits`, `/api/withdrawals`, `/api/transfers`, `/api/transfers/{id}/reversal`) require a unique UUIDv4 string in the HTTP Header:
  ```http
  Idempotency-Key: 9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d
  ```
- **Currency**: All amounts are strictly in **`INR`**, formatted as strings or exact two-decimal numbers (e.g., `"1000.00"`). Requests with $>2$ decimal places or non-positive amounts are rejected.
- **Error Format**: RFC 7807 problem details JSON format:
  ```json
  {
    "status": 400,
    "error": "Bad Request",
    "message": "Validation failed for request arguments",
    "path": "/api/auth/register",
    "timestamp": "2026-11-06T11:56:39.652Z",
    "fieldErrors": {
      "password": "Password must be between 8 and 128 characters"
    }
  }
  ```

---

## 2. Role-Based Access Control Matrix

| Endpoint | Method | Allowed Roles | Account Permission | Description & Financial Effect |
| :--- | :--- | :--- | :--- | :--- |
| `/api/auth/register` | POST | Public | None | Registers new customer as `ACCOUNT_HOLDER` |
| `/api/auth/login` | POST | Public | None | Authenticates with email/password, issues JWT |
| `/api/auth/me` | GET | Any Authenticated | None | Returns authenticated user profile |
| `/api/admin/users` | GET | `ADMINISTRATOR` | None | Lists all users with roles and status |
| `/api/admin/users` | POST | `ADMINISTRATOR` | None | Provisions user with any role (`ADMINISTRATOR`, `AUDITOR`, `ACCOUNT_HOLDER`) |
| `/api/admin/system-info` | GET | `ADMINISTRATOR` | None | Returns system health, total user count |
| `/api/audit/records` | GET | `AUDITOR`, `ADMINISTRATOR` | None | Strictly read-only audit inspection |
| `/api/audit/records` | POST/PUT/DELETE | None | None | **Deny All** (`403 Forbidden`) — Auditor write prohibition |
| `/api/accounts` | POST | `ACCOUNT_HOLDER` | None | Creates new customer account with `0.00 INR` |
| `/api/accounts` | GET | Any Authenticated | None | Lists accounts authorized for current user |
| `/api/accounts/{id}` | GET | Any Authenticated | `CAN_VIEW_STATEMENT` | Returns account details |
| `/api/accounts/{id}/balance` | GET | Any Authenticated | `CAN_VIEW_STATEMENT` | Returns account balance |
| `/api/accounts/{id}/statement` | GET | Any Authenticated | `CAN_VIEW_STATEMENT` | Returns chronological statement |
| `/api/admin/accounts` | GET | `ADMINISTRATOR` | None | Lists all accounts across all users |
| `/api/admin/accounts` | POST | `ADMINISTRATOR` | None | Provisions account for target user with `0.00 INR` |
| `/api/admin/accounts/{id}/status` | PATCH | `ADMINISTRATOR` | None | Updates status (`ACTIVE`, `FROZEN`, `CLOSED`) |
| `/api/deposits` | POST | `ACCOUNT_HOLDER` | `CAN_DEPOSIT` | Increases customer balance; Debits Clearing |
| `/api/withdrawals` | POST | `ACCOUNT_HOLDER` | `CAN_WITHDRAW` | Decreases customer balance; Credits Clearing |
| `/api/transfers` | POST | `ACCOUNT_HOLDER` | `CAN_TRANSFER` (on source) | Reallocates funds between two customer accounts |
| `/api/transfers/{id}/reversal` | POST | `ADMINISTRATOR` | `CAN_ADJUST` | Compensating transfer reversing previous tx |

> [!NOTE]
> The **`AUDITOR`** role has strictly **Read-Only** access. Any request by an auditor to mutating operations (`POST`, `PUT`, `DELETE` on `/api/accounts`, `/api/admin/**`, `/api/audit/**` or financial mutation endpoints) is rejected with HTTP `403 Forbidden`.

---

## 3. Core Endpoints

### 3.1 Authentication & Profile

#### `POST /api/auth/register`
Public registration endpoint for new customers. Unconditionally assigns role `ACCOUNT_HOLDER` (client-supplied roles in payload are ignored).

**Request Body**:
```json
{
  "username": "john_doe",
  "email": "john@ledgerline.test",
  "password": "SecurePassword123"
}
```

**Response (201 Created)**:
```json
{
  "id": 1,
  "username": "john_doe",
  "email": "john@ledgerline.test",
  "role": "ACCOUNT_HOLDER",
  "active": true,
  "createdAt": "2026-11-06T11:56:39.652Z"
}
```
*Note: The password and password hash are never returned in responses.*

**Error Responses**:
- `400 Bad Request`: Validation failure (username blank/non-alphanumeric, invalid email, password length < 8 or > 128 chars).
- `409 Conflict`: Username or email already registered.

#### `POST /api/auth/login`
Authenticates a user with email and password, returning a signed HMAC-SHA256 JWT.

**Request Body**:
```json
{
  "email": "holder@ledgerline.test",
  "password": "ChosenPassword123"
}
```

**Response (200 OK)**:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresInMs": 86400000,
  "user": {
    "id": 2,
    "username": "holder",
    "email": "holder@ledgerline.test",
    "role": "ACCOUNT_HOLDER",
    "active": true,
    "createdAt": "2026-11-06T11:50:00Z"
  }
}
```

**Error Responses**:
- `401 Unauthorized`: Invalid email or password, or deactivated user account.

#### `GET /api/auth/me`
Retrieves authenticated user profile based on the supplied JWT Bearer token.

**Headers**:
```http
Authorization: Bearer <jwt-token>
```

**Response (200 OK)**:
```json
{
  "id": 2,
  "username": "holder",
  "email": "holder@ledgerline.test",
  "role": "ACCOUNT_HOLDER",
  "active": true,
  "createdAt": "2026-11-06T11:50:00Z"
}
```

**Error Responses**:
- `401 Unauthorized`: Missing, expired, malformed, or tampered JWT token.

---

### 3.2 Administration Endpoints (Admin Only)

#### `GET /api/admin/system-info`
Returns system status, active user count, and timestamp. Requires `ADMINISTRATOR` role.

**Headers**:
```http
Authorization: Bearer <admin-jwt-token>
```

**Response (200 OK)**:
```json
{
  "application": "LedgerLine",
  "status": "HEALTHY",
  "totalUsers": 3,
  "timestamp": "2026-11-06T11:56:39.652Z"
}
```

**Error Responses**:
- `401 Unauthorized`: Unauthenticated request.
- `403 Forbidden`: Authenticated user lacks `ADMINISTRATOR` role (`ACCOUNT_HOLDER` or `AUDITOR`).

#### `GET /api/admin/users`
Lists all registered users in the system without exposing password hashes. Requires `ADMINISTRATOR` role.

**Response (200 OK)**:
```json
[
  {
    "id": 1,
    "username": "admin",
    "email": "admin@ledgerline.test",
    "role": "ADMINISTRATOR",
    "active": true,
    "createdAt": "2026-11-06T11:50:00Z"
  },
  {
    "id": 2,
    "username": "auditor",
    "email": "auditor@ledgerline.test",
    "role": "AUDITOR",
    "active": true,
    "createdAt": "2026-11-06T11:50:00Z"
  }
]
```

#### `POST /api/admin/users`
Provisions an initial or additional user with any explicit role (`ADMINISTRATOR`, `AUDITOR`, `ACCOUNT_HOLDER`). Requires `ADMINISTRATOR` role.

**Request Body**:
```json
{
  "username": "compliance_auditor",
  "email": "compliance@ledgerline.test",
  "password": "SecurePassword123",
  "role": "AUDITOR"
}
```

**Response (201 Created)**: Returns the newly provisioned `UserResponse` with `status: 201`.

---

### 3.3 Audit Endpoints (Auditor & Admin)

#### `GET /api/audit/records`
Read-only inspection endpoint accessible to `AUDITOR` and `ADMINISTRATOR`. Returns audit status and records.

**Response (200 OK)**:
```json
{
  "status": "ACTIVE",
  "auditorAccess": true,
  "readOnly": true,
  "recordsCount": 0,
  "timestamp": "2026-11-06T11:56:39.652Z"
}
```

**Error Responses**:
- `401 Unauthorized`: Unauthenticated.
- `403 Forbidden`: `ACCOUNT_HOLDER` role attempting audit access.

#### `POST /api/audit/records`
Explicitly barred by security configuration. All write methods (`POST`, `PUT`, `DELETE`) return `403 Forbidden` for all roles, enforcing immutability of the audit stream.

---

### 3.2 Account Management & Retrieval (Milestone 3)

#### `POST /api/accounts`
Creates a new customer checking/savings account for the authenticated `ACCOUNT_HOLDER`.
- The server obtains the account owner's identity strictly from the authenticated JWT principal.
- Client attempts to set initial balance or owner ID are ignored.
- Account is initialized with `0.00 INR` balance and `ACTIVE` status.
- Atomically assigns standard customer permissions (`CAN_DEPOSIT`, `CAN_WITHDRAW`, `CAN_TRANSFER`, `CAN_VIEW_STATEMENT`).

**Headers**:
```http
Authorization: Bearer <jwt-token>
Content-Type: application/json
```

**Request Body**:
```json
{
  "name": "Primary Checking",
  "currency": "INR"
}
```

**Response (201 Created)**:
```json
{
  "id": 101,
  "accountNumber": "ACC-2026-F3C28B1D4E",
  "name": "Primary Checking",
  "accountType": "CUSTOMER",
  "currency": "INR",
  "balance": 0.00,
  "statementSequenceCounter": 0,
  "status": "ACTIVE",
  "createdAt": "2026-11-06T12:00:00Z",
  "updatedAt": "2026-11-06T12:00:00Z"
}
```

**Error Responses**:
- `400 Bad Request`: Validation failure (blank name, name > 100 chars, or currency != `INR`).
- `401 Unauthorized`: Unauthenticated request.
- `403 Forbidden`: `AUDITOR` attempting account creation.

#### `GET /api/accounts`
Retrieves all accounts accessible to the caller:
- `ACCOUNT_HOLDER`: returns only accounts explicitly granted to the user in `account_permissions`.
- `ADMINISTRATOR` / `AUDITOR`: returns all accounts across the system.

**Response (200 OK)**:
```json
[
  {
    "id": 101,
    "accountNumber": "ACC-2026-F3C28B1D4E",
    "name": "Primary Checking",
    "accountType": "CUSTOMER",
    "currency": "INR",
    "balance": 0.00,
    "statementSequenceCounter": 0,
    "status": "ACTIVE",
    "createdAt": "2026-11-06T12:00:00Z",
    "updatedAt": "2026-11-06T12:00:00Z"
  }
]
```

#### `GET /api/accounts/{id}`
Returns details for a specific account. Validates that the caller holds `CAN_VIEW_STATEMENT` permission (or role `AUDITOR` / `ADMINISTRATOR`). Non-existent accounts and unauthorized attempts return `403 Forbidden` to prevent IDOR account enumeration.

**Response (200 OK)**:
```json
{
  "id": 101,
  "accountNumber": "ACC-2026-F3C28B1D4E",
  "name": "Primary Checking",
  "accountType": "CUSTOMER",
  "currency": "INR",
  "balance": 0.00,
  "statementSequenceCounter": 0,
  "status": "ACTIVE",
  "createdAt": "2026-11-06T12:00:00Z",
  "updatedAt": "2026-11-06T12:00:00Z"
}
```

**Error Responses**:
- `401 Unauthorized`: Unauthenticated.
- `403 Forbidden`: Access denied or account non-existent.

#### `GET /api/accounts/{id}/balance`
Returns balance and active status for an authorized account.

**Response (200 OK)**:
```json
{
  "accountId": 101,
  "accountNumber": "ACC-2026-F3C28B1D4E",
  "currency": "INR",
  "balance": 0.00,
  "status": "ACTIVE",
  "asOf": "2026-11-06T12:15:00Z"
}
```

#### `GET /api/admin/accounts`
Administrative endpoint listing all accounts across all users. Requires `ADMINISTRATOR` role.

**Response (200 OK)**: Returns JSON array of `AccountResponse` objects.

#### `POST /api/admin/accounts`
Administrative endpoint allowing an administrator to provision an account for an existing user. Requires `ADMINISTRATOR` role.

**Request Body**:
```json
{
  "userId": 2,
  "name": "Corporate Payroll",
  "accountType": "CUSTOMER",
  "currency": "INR"
}
```

**Response (201 Created)**: Returns the newly provisioned `AccountResponse`.

#### `PATCH /api/admin/accounts/{id}/status`
Administrative endpoint to change an account's operational status (`ACTIVE`, `FROZEN`, `CLOSED`). Does not alter account balances or historical ledger records. Requires `ADMINISTRATOR` role.

**Request Body**:
```json
{
  "status": "FROZEN"
}
```

**Response (200 OK)**: Returns updated `AccountResponse` with new status.

---

### 3.3 Statements (Upcoming Milestone 4)

#### `GET /api/accounts/{id}/statement`
Retrieves chronological statement ordered strictly by `sequenceNumber ASC`.

**Response (200 OK)**:
```json
{
  "accountId": 101,
  "accountNumber": "ACC-2026-0001",
  "currentBalance": "400.00",
  "currency": "INR",
  "events": [
    {
      "sequenceNumber": 1,
      "eventType": "POSTED_CREDIT",
      "amount": "1000.00",
      "runningBalance": "1000.00",
      "status": "POSTED",
      "initiatingUserId": 2,
      "transactionId": "tx-uuid-1",
      "timestamp": "2026-11-06T10:00:00Z"
    },
    {
      "sequenceNumber": 2,
      "eventType": "REFUSED_WITHDRAWAL",
      "amount": "5000.00",
      "runningBalance": "1000.00",
      "status": "REFUSED",
      "reason": "INSUFFICIENT_FUNDS",
      "initiatingUserId": 2,
      "transactionId": null,
      "timestamp": "2026-11-06T10:05:00Z"
    },
    {
      "sequenceNumber": 3,
      "eventType": "POSTED_DEBIT",
      "amount": "400.00",
      "runningBalance": "600.00",
      "status": "POSTED",
      "initiatingUserId": 2,
      "transactionId": "tx-uuid-2",
      "timestamp": "2026-11-06T10:10:00Z"
    },
    {
      "sequenceNumber": 4,
      "eventType": "POSTED_DEBIT",
      "amount": "200.00",
      "runningBalance": "400.00",
      "status": "POSTED",
      "initiatingUserId": 2,
      "transactionId": "tx-uuid-3",
      "timestamp": "2026-11-06T10:15:00Z"
    }
  ]
}
```

---

### 3.3 Financial Operations

#### `POST /transfers`
Transfers money between two customer accounts.

**Headers**:
- `Authorization: Bearer <jwt-token>`
- `Idempotency-Key: <unique-uuid>`

**Request Body**:
```json
{
  "sourceAccountId": 101,
  "destinationAccountId": 102,
  "amount": "400.00",
  "currency": "INR",
  "description": "Invoice 104 payment"
}
```

**Response (200 OK)**:
```json
{
  "transactionId": "c4b1d61a-0982-4f32-8df2-e91845183421",
  "referenceId": "TXN-20261106-49120",
  "type": "TRANSFER",
  "status": "POSTED",
  "sourceAccountId": 101,
  "destinationAccountId": 102,
  "amount": "400.00",
  "currency": "INR",
  "completedAt": "2026-11-06T10:10:00Z"
}
```

**Refusal Response (422 Unprocessable Entity)**:
```json
{
  "type": "https://api.ledgerline.test/errors/insufficient-funds",
  "title": "Insufficient Funds",
  "status": 422,
  "detail": "Account 101 has balance 400.00 INR, which is insufficient for transfer of 1000.00 INR.",
  "instance": "/api/v1/transfers"
}
```

---

## 4. Standardized Error Format (RFC 7807)

All non-2xx responses follow RFC 7807 `ProblemDetail`:
```json
{
  "type": "https://api.ledgerline.test/errors/idempotency-payload-mismatch",
  "title": "Conflict",
  "status": 409,
  "detail": "Idempotency key '9b1deb4d...' was already used with a different request payload.",
  "timestamp": "2026-11-06T10:12:00Z"
}
```
