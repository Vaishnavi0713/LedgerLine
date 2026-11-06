# LedgerLine — REST API Specification

## 1. Overview & Protocol Standards

- **Base URL**: `/api/v1`
- **Content-Type**: `application/json`
- **Authentication**: Stateless Bearer Token passed via HTTP Header:
  ```http
  Authorization: Bearer <jwt-token>
  ```
- **Idempotency**: All mutating operations (`POST`) require a unique UUIDv4 string in the HTTP Header:
  ```http
  Idempotency-Key: 9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d
  ```
- **Currency**: All amounts are strictly in **`INR`**, formatted as strings or exact two-decimal numbers (e.g., `"1000.00"`).

---

## 2. Role-Based Access Control Matrix

| Endpoint | Method | Required Role | Required Account Permission | Financial Effect |
| :--- | :--- | :--- | :--- | :--- |
| `/auth/login` | POST | Public | None | None (Token issuance) |
| `/accounts` | POST | `ADMINISTRATOR`, `ACCOUNT_HOLDER` | None | Creates new account with `0.00 INR` |
| `/accounts` | GET | Any Authenticated | None | Lists user's authorized accounts |
| `/accounts/{id}/balance`| GET | Any Authenticated | `CAN_VIEW_STATEMENT` | Returns current balance |
| `/accounts/{id}/statement`| GET | Any Authenticated | `CAN_VIEW_STATEMENT` | Returns chronological statement |
| `/deposits` | POST | `ACCOUNT_HOLDER` | `CAN_DEPOSIT` | Increases customer balance; Debits Clearing |
| `/withdrawals` | POST | `ACCOUNT_HOLDER` | `CAN_WITHDRAW` | Decreases customer balance; Credits Clearing |
| `/transfers` | POST | `ACCOUNT_HOLDER` | `CAN_TRANSFER` (on source) | Reallocates funds between two accounts |
| `/transfers/{id}/reversal`| POST | `ADMINISTRATOR` | `CAN_ADJUST` | Compensating transfer reversing previous tx |
| `/audit/logs` | GET | `AUDITOR`, `ADMINISTRATOR` | None | Reads security audit records |

> [!NOTE]
> The **`AUDITOR`** role has strictly **Read-Only** access. Any request by an auditor to `/deposits`, `/withdrawals`, `/transfers`, `/accounts` (POST), or `/reversal` is rejected with HTTP `403 Forbidden`.

---

## 3. Core Endpoints

### 3.1 Authentication

#### `POST /auth/login`
Authenticates user and returns a stateless JWT.

**Request Body**:
```json
{
  "username": "holder@ledgerline.test",
  "password": "ChosenPassword123!"
}
```

**Response (200 OK)**:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresInMs": 86400000,
  "role": "ACCOUNT_HOLDER",
  "username": "holder@ledgerline.test"
}
```

---

### 3.2 Account Management & Statements

#### `GET /accounts/{id}/balance`
Returns account balance and active status.

**Response (200 OK)**:
```json
{
  "accountId": 101,
  "accountNumber": "ACC-2026-0001",
  "currency": "INR",
  "balance": "400.00",
  "status": "ACTIVE"
}
```

#### `GET /accounts/{id}/statement`
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
