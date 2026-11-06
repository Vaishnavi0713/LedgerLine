# LedgerLine — Threat Model & Security Specification

This document details the STRIDE-based threat analysis, specific financial attack vectors, implemented mitigations, and accepted risks for the LedgerLine system.

---

## 1. STRIDE Threat Matrix

| STRIDE Category | Threat Description | LedgerLine Mitigation | Residual Risk Level |
| :--- | :--- | :--- | :--- |
| **Spoofing** | Attacker impersonates an authorized user to move funds. | HMAC-SHA256 JWT tokens with short expiration, BCrypt (cost factor 12) password hashing, and strict signature verification. | Low |
| **Tampering** | Attacker modifies historical ledger entries or balances in database. | MySQL append-only triggers (`SQLSTATE 45000`), runtime user privilege revocation (`NO UPDATE/DELETE`), and hash chain integrity. | Low |
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

### Threat 4: Ledger Tampering & History Rewriting (CWE-471)
- **Attack Scenario**: A malicious employee or compromised application user executes `UPDATE ledger_entries SET amount = 0`.
- **Mitigation**:
  1. Runtime user (`ledgerline_app`) is granted strictly `SELECT` and `INSERT` on `ledger_entries` and `statement_events`.
  2. Database triggers `BEFORE UPDATE` and `BEFORE DELETE` abort any attempted mutation with `SQLSTATE 45000`.

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
  3. Java uses `BigDecimal` with scale 2. Requests with $>2$ decimal places are rejected at the DTO validation layer.

---

## 3. Accepted Risks & Operational Boundaries

1. **Database Superuser Compromise**:
   - *Risk*: A rogue DBA or attacker gaining MySQL `root` access can drop triggers or truncate tables.
   - *Accepted Mitigation*: Triggers defend against application-tier compromise. Root-level actions must be audited using external MySQL enterprise audit logs or write-once-read-many (WORM) binary logs.
2. **Single Database Scope**:
   - *Risk*: Distributed database split-brain.
   - *Scope Decision*: LedgerLine is designed for a single primary ACID database instance with InnoDB row locks. Multi-master distributed transactions are intentionally out of scope.
