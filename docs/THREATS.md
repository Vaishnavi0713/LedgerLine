# LedgerLine — Threat Model & Security Specification

This document details the STRIDE-based threat analysis, specific financial attack vectors, implemented mitigations, and accepted risks for the LedgerLine system.

---

## 1. STRIDE Threat Matrix

| STRIDE Category | Threat Description | LedgerLine Mitigation | Residual Risk Level |
| :--- | :--- | :--- | :--- |
| **Spoofing** | Attacker impersonates an authorized user to move funds. | HMAC-SHA256 JWT tokens with short expiration, BCrypt (cost factor 12) password hashing, and strict signature verification. | Low |
| **Tampering** | Attacker modifies historical ledger entries or balances in database. | Dual-layer defense: MySQL least-privilege user grants (`NO UPDATE/DELETE` on ledger/statement tables) plus MySQL append-only triggers (`SQLSTATE 45000`). | Low |
| **Repudiation** | User claims they did not initiate a transaction. | Every ledger entry and statement event logs `initiating_user_id`, timestamp, and idempotency key. Security actions logged to `audit_records`. | Low |
| **Information Disclosure** | User B discovers Account A's balance or transaction history. | Account-level authorization (`account_permissions`), query filtering by authenticated user, and suppression of target account details in refusal audit records. | Low |
| **Denial of Service** | Flooding transfer requests to cause deadlocks or exhaust connection pools. | Deterministic lock ordering (`min(id)` then `max(id)`), HikariCP pool limits, and single-transaction idempotency short-circuiting. | Low |
| **Elevation of Privilege** | Normal user accesses administrative endpoints or Auditor attempts financial modification. | Spring Security role-based method security (`@PreAuthorize`), Auditor role enforcement (strictly read-only on all endpoints). | Low |

---

## 2. Deep Dive: Financial Attack Vectors & Mitigations

### Threat 1: Race Condition / Double-Spending (CWE-362)
- **Attack Scenario**: Attacker with ₹500 balance sends two simultaneous transfer requests for ₹500 to two different accounts within the same millisecond.
- **Vulnerability**: Read-Check-Write gap (TOCTOU).
- **Mitigation**:
  1. Account rows locked exclusively with `SELECT ... FOR UPDATE`.
  2. Deterministic lock ordering prevents deadlocks.
  3. Second thread waits for lock release, then discovers balance has dropped to `₹0.00`.
  4. Database constraint `CHECK (balance >= 0.00)` enforces invariant at the engine level.

### Threat 2: Insecure Direct Object Reference (IDOR) (CWE-639)
- **Attack Scenario**: User B issues a transfer request with `sourceAccountId: 101` (owned by User A).
- **Mitigation**:
  1. Server never relies on frontend-supplied authorization.
  2. `AccountSecurityService` validates that the authenticated principal possesses the `CAN_TRANSFER` permission for `sourceAccountId`.
  3. Attempt is rejected with HTTP `403 Forbidden` and logged in `audit_records` (without recording the unauthorized attempt on User A's private statement).

### Threat 3: Replay & Network Retries (CWE-294)
- **Attack Scenario**: Network drops before client receives transfer confirmation; client or attacker re-submits the HTTP request.
- **Mitigation**:
  1. Mandatory `Idempotency-Key` header scoped by `(user_id, operation_type, idempotency_key)`.
  2. First request stores the final HTTP status and response payload atomically.
  3. Replay matches the existing record and returns the cached response with zero financial re-execution.
  4. If payload differs, returns `409 Conflict`.

### Threat 4: Ledger Tampering, History Rewriting & Unbalanced Inserts (CWE-471)
- **Attack Scenario**: A malicious actor or compromised application session attempts `UPDATE ledger_entries SET amount = 0`, `DELETE FROM ledger_entries`, executes raw `UPDATE accounts SET balance = ...`, creates fabricated `POSTED` transactions, or inserts arbitrary/unbalanced single-sided ledger rows.
- **Four-Tier Defense Mitigation**:
  1. **User Privilege Layer & Revoked Direct Writes**: The application runtime user (`ledgerline_app`) is granted `SELECT` only across `ledgerline_db.*`. Direct `INSERT`, `UPDATE`, and `DELETE` on `ledger_entries` and `statement_events` are completely denied (Error `1142`). On `accounts`, `UPDATE` is strictly column-restricted to `(name, status)`; direct manipulation of `balance` and `statement_sequence_counter` is denied (`ERROR 1142`).
  2. **Dedicated Routine Definer (`ledgerline_definer`)**: Stored procedures execute with `SQL SECURITY DEFINER` under a dedicated definer user with narrow DML privileges only (no DDL, no GRANT, no DELETE).
  3. **Stored Procedure Hardened Write Boundary & Spring Transaction Ownership**: `sp_post_ledger_pair` requires an existing `PENDING` transaction, locks accounts deterministically, derives resulting balances and monotonic sequence numbers from persisted account state, validates caller expectations, updates accounts, inserts balanced `DEBIT` and `CREDIT` entries, and transitions the transaction to `POSTED`. Crucially, stored procedures contain zero internal `ROLLBACK` or `COMMIT` statements; errors are propagated directly via `RESIGNAL` to Spring (`@Transactional(rollbackFor = Exception.class)`), ensuring the calling Java application controls the complete 9-step atomic boundary and rolls back the entire operation on any failure. Statement timelines are similarly safeguarded via `sp_record_statement_event`.
  4. **Engine Constraints & Immutability Triggers**:
     - `uq_ledger_tx_entry UNIQUE (transaction_id, entry_type)` prevents duplicate postings per transaction.
     - `trg_tx_prevent_insert_posted` blocks direct creation of `POSTED` transactions.
     - `trg_tx_prevent_finalized_update` blocks updating finalized transactions.
     - `BEFORE UPDATE` and `BEFORE DELETE` triggers abort any attempted mutation on `ledger_entries` and `statement_events` (`SQLSTATE 45000`).
- **Important Security Distinction**:
  - Triggers and least-privilege grants protect against compromised application sessions and non-admin database accounts.
  - They **do not** prevent actions by a database administrator with `SUPER` or `SYSTEM_VARIABLES_ADMIN` privileges (who can drop triggers or grant permissions). Administrative accounts must be strictly isolated.

### Threat 5: Duplicate Reversal Exploits
- **Attack Scenario**: Attacker executes a reversal on a transfer multiple times, draining recipient funds.
- **Mitigation**:
  1. MySQL virtual generated column `reversal_target_id` stores `original_transaction_id` only for `POSTED` reversals.
  2. Unique constraint `uq_single_successful_reversal` on `reversal_target_id` prevents duplicate successful reversals at the database level.

### Threat 6: Floating-Point Precision Drift (CWE-682)
- **Attack Scenario**: Fractional cent accumulation or rounding errors (e.g. `0.1 + 0.2 = 0.30000000000000004`).
- **Mitigation**:
  1. `float` and `double` are strictly prohibited.
  2. Database uses `DECIMAL(19, 2)` for all monetary columns.
  3. Java uses `BigDecimal` with scale 2. Requests with $>2$ decimal places are rejected at the DTO validation layer. No silent rounding is permitted.

### Threat 7: Credential Stuffing & Offline Hash Cracking (CWE-307 / CWE-916)
- **Attack Scenario**: Attacker attempts automated password guessing or attempts to crack password hashes retrieved from database dumps.
- **Mitigation**:
  1. Password hashing uses `BCryptPasswordEncoder` with strength (cost factor) 12, introducing deliberate computational overhead against GPU/ASIC hash cracking.
  2. Plaintext passwords and hash strings are excluded from all DTO responses (`UserResponse`, `AuthResponse`).
  3. Password policy requires 8–128 characters, enforced at the validation boundary.

### Threat 8: JWT Forgery, Key Downgrade & Signature Bypass (CWE-347)
- **Attack Scenario**: Attacker modifies JWT token claims (e.g. changes `role: ACCOUNT_HOLDER` to `role: ADMINISTRATOR` or sets `alg: none`).
- **Mitigation**:
  1. Strong cryptographic signing using HMAC-SHA256 (`HS256`) via JJWT.
  2. Startup validation requires minimum 256-bit (32-byte) secret key, throwing `IllegalStateException` on weak secrets.
  3. Signature, expiration timestamp, and claims structure are strictly validated on every non-public request by `JwtTokenProvider` and `JwtAuthenticationFilter`.
  4. Tampered tokens immediately trigger `JwtAuthenticationEntryPoint` returning HTTP `401 Unauthorized`.

### Threat 9: Privilege Escalation via Registration Payload Tampering (CWE-269)
- **Attack Scenario**: An attacker submits a POST request to public `/api/auth/register` with extra JSON fields such as `"role": "ADMINISTRATOR"` or `"role": "AUDITOR"`.
- **Mitigation**:
  1. The public registration DTO (`RegisterRequest`) deliberately lacks a `role` field.
  2. The service layer (`AuthService.register`) unconditionally assigns `UserRole.ACCOUNT_HOLDER` to all public registrations.
  3. Privileged roles (`ADMINISTRATOR`, `AUDITOR`) can only be provisioned by authenticated administrators via `/api/admin/users` or through controlled environment variable bootstrapping (`UserBootstrapService`).

### Threat 10: Insecure Direct Object Reference (IDOR) & Account Enumeration (CWE-639 / CWE-200)
- **Attack Scenario**: Authenticated User B attempts to access User A's account or probes sequential account IDs to detect valid accounts.
- **Mitigation**:
  1. `AccountAuthorizationService` checks explicit permissions in `account_permissions` for each user/account pair.
  2. If an account does not exist or the user lacks access, the service throws `AccountAccessDeniedException` (HTTP `403 Forbidden`) rather than `ResourceNotFoundException` (HTTP `404 Not Found`).
  3. Attackers cannot determine whether an account ID exists or merely lacks authorization.

### Threat 11: Auditor Write Abuse & Compliance Bypass (CWE-285)
- **Attack Scenario**: An auditor user attempts to modify audit trails, perform deposits, or initiate transfers.
- **Mitigation**:
  1. `SecurityConfig` explicitly denies all write methods (`POST`, `PUT`, `DELETE`) on `/api/audit/**` with `denyAll()`.
  2. `AccountAuthorizationService` strictly denies mutating permissions (`CAN_DEPOSIT`, `CAN_WITHDRAW`, `CAN_TRANSFER`, `CAN_ADJUST`) to users with the `AUDITOR` role, granting only `CAN_VIEW_STATEMENT`.

### Threat 12: Artificial Opening Balance Injection / Unauthorized Money Creation (CWE-471 / CWE-840)
- **Attack Scenario**: A malicious client submits `{"name": "Savings", "balance": 100000.00}` to `POST /api/accounts` attempting to conjure funds out of nothingness upon account creation.
- **Mitigation**:
  1. `CreateAccountRequest` DTO contains zero monetary fields (`balance`, `initialBalance` are neither bound nor recognized).
  2. `AccountService.createAccount` hardcodes `balance = BigDecimal.ZERO.setScale(2)`.
  3. All balance modifications must flow strictly through the double-entry ledger posting routine (`sp_post_ledger_pair`).

### Threat 13: Identity Spoofing in Account Creation (CWE-287 / CWE-639)
- **Attack Scenario**: Attacker submits `POST /api/accounts` with `{"userId": 1, "ownerId": 1}` attempting to create an account assigned to another customer or administrator.
- **Mitigation**:
  1. `CreateAccountRequest` does not accept user IDs.
  2. Account ownership is derived strictly from the verified JWT principal in `SecurityContextHolder`.
  3. Only administrators can provision accounts for other users via the dedicated `/api/admin/accounts` endpoint, which enforces `ADMINISTRATOR` role verification.

### Threat 14: Inactive / Frozen Account Exploits (CWE-668 / CWE-284)
- **Attack Scenario**: A user attempts to execute financial operations on a frozen or closed account.
- **Mitigation**:
  1. Administrative status management (`PATCH /api/admin/accounts/{id}/status`) allows freezing or closing accounts.
  2. `AccountAuthorizationService.requireActiveAccount` validates that accounts are in `ACTIVE` status before any financial operation can proceed, rejecting inactive accounts with `AccountAccessDeniedException`.
  3. Read-only inspection (statement and balance queries) remains accessible for auditing and dispute resolution.

### Threat 15: Cross-User Account Enumeration & Information Disclosure (CWE-200 / CWE-639)
- **Attack Scenario**: Attacker iterates sequential account IDs (`/api/accounts/1`, `/api/accounts/2`) to discover existing accounts and infer customer balances.
- **Mitigation**:
  1. Non-existent accounts and unauthorized accounts both return HTTP `403 Forbidden` (`AccountAccessDeniedException`).
  2. Error responses never disclose whether the account ID exists.
  3. Account numbers are generated using cryptographically secure random tokens (`ACC-YYYY-XXXXXXXXXX`), preventing sequential enumeration.

---

## 3. Accepted Risks & Operational Boundaries

1. **Privileged Database Administrator (DBA) Access**:
   - *Risk*: A rogue DBA or attacker possessing MySQL `root` credentials can drop triggers, truncate tables, or directly modify data files.
   - *Architectural Reality*: Database triggers and least-privilege runtime users protect against application-tier vulnerabilities, not physical DBA compromise.
   - *Operational Countermeasure*: Physical root access must be isolated, migration user credentials kept outside the runtime container, and write-once-read-many (WORM) audit logs used for external DBA monitoring.
2. **Single Database Scope**:
   - *Risk*: Distributed database split-brain.
   - *Scope Decision*: LedgerLine is designed for a single primary ACID database instance with InnoDB row locks. Multi-master distributed transactions are intentionally out of scope.
