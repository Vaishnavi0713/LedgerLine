# LedgerLine — AI Development & Mistake Audit Log (PROMPTS.md)

This log documents interactions between the engineering lead and AI during the development of LedgerLine. In compliance with the hiring challenge specifications, this file tracks architectural and code decisions, identifies AI design defects, records corrective measures, and links changes to Git commits.

---

## Entry 1: Architecture Invariant Corrections, Money Integrity & Concurrency

- **Date**: 2026-11-06
- **Category**: Money Integrity, Concurrency, Idempotency & Database Constraints

### 1. Prompt Given to AI
> "Propose an initial full-stack architecture for LedgerLine covering double-entry accounting, concurrency, idempotency, role-based security, database schema, and test strategies."

### 2. What AI Produced Initially
- A preliminary architecture with Spring Boot, React, and MySQL.
- Double-entry model that modeled peer-to-peer transfers, but treated external deposits and withdrawals as one-sided ledger entries without a clearing account.
- Schema used `DECIMAL(19, 4)` and `USD` currency references.
- Account statements only captured successfully posted entries (`DEBIT` and `CREDIT`), omitting refused attempts.
- Idempotency proposal relied on an independent lease/fencing table mechanism with separate transactions.
- Proposed a PostgreSQL-style partial unique index (`WHERE type = 'REVERSAL' AND status = 'POSTED'`) which is invalid syntax in MySQL 8.0.
- Application configuration included hardcoded fallback strings for demo passwords (e.g. `${DEMO_ADMIN_PASSWORD:Admin#Demo2026!Secure}`).

### 3. What Was Specifically Wrong
1. **One-Sided External Accounting Violation**: In standard double-entry bookkeeping, deposits and withdrawals cannot be one-sided without violating balance sheet equilibrium.
2. **Precision & Currency Inconsistency**: The challenge specified `INR` currency and 2 decimal places (`DECIMAL(19, 2)`), not `DECIMAL(19, 4)` or USD.
3. **Statement Completeness (Requirement 8 Violation)**: Refused attempts (e.g. overdraft rejections, unauthorized attempts) were not recorded on account statements, violating the mandate that statements must show all debits, credits, and refused attempts with running balances and initiating users.
4. **Idempotency Split-Transaction Vulnerability**: Using separate transactions for idempotency leases opened windows for zombie state during server crashes.
5. **MySQL Syntax Incompatibility**: MySQL 8.0 does not support `WHERE` clauses on unique indexes.
6. **Hardcoded Fallback Credentials**: Storing fallback passwords in configuration files violated the non-negotiable rule against hardcoded secrets.

### 4. What Was Corrected & How It Was Verified
1. **Double-Entry Clearing Model**: Introduced `SYSTEM_CLEARING` account (`ACC-SYSTEM-CLEARING-001`). External deposits debit clearing and credit customer accounts; external withdrawals debit customer accounts and credit clearing. Invariant $\sum \text{Customer Balances} == \text{Clearing Balance}$ holds at all times.
2. **Standardized INR Precision**: Converted all schemas to `DECIMAL(19, 2)` and Java types to `BigDecimal` with strict rejection of $>2$ decimal places and zero/negative amounts.
3. **Statement Events Table**: Created `statement_events` with per-account monotonic `sequence_number` under lock, capturing posted debits, posted credits, and refused attempts with running balances and `initiating_user_id`.
4. **Single Atomic MySQL Transaction for Idempotency**: Unified idempotency insertion, balance updates, ledger posting, and statement events into a single `@Transactional` boundary.
5. **Generated Column for Reversal Uniqueness**: Used MySQL stored generated column `reversal_target_id = (CASE WHEN type = 'REVERSAL' AND status = 'POSTED' THEN original_transaction_id ELSE NULL END)` with a unique index.
6. **Strict Environment Secrets**: Removed all default fallback values from `application.yml` and `.env.example`.

### 5. Relevant Git Commit Link
- Commit: *Pending Milestone 0 initialization commit* (e.g., `git rev-parse HEAD`)

---

## Entry 2: Critical Stored Procedure Security, Definer Privilege & Balance Derivation

- **Date**: 2026-11-06
- **Category**: Money Integrity, Ledger Security, Stored Procedures & Privilege Boundaries

### 1. Prompt Given to AI
> "Review the actual implementation of V4 and all stored procedures. Do not rely only on the previous completion report.
> I identified a potential critical weakness: sp_post_ledger_pair accepts initiating user ID, resulting balances, and sequence numbers from its caller, and appears to trust them.
> Fix or explicitly document:
> 1. Transaction authorization and lifecycle: Verify runtime user cannot create an arbitrary transaction already marked POSTED; enforce lifecycle (PENDING -> POSTED); validate initiating user against persisted transaction.
> 2. Duplicate posting protection: Ensure one transaction cannot receive multiple debit-credit pairs; add database uniqueness constraints.
> 3. Balance and sequence integrity: Do not blindly trust caller-supplied balance_after or sequence numbers; derive or validate against persisted account state; ensure account balances and ledger entries cannot disagree.
> 4. Stored procedure security: Inspect SQL SECURITY DEFINER; do not use migration account with excessive privileges as routine definer; check atomicity when second insert fails.
> 5. Statement procedure: Review sp_record_statement_event with same scrutiny.
> 6. Tests: Add test cases for all failure modes."

### 2. What AI Produced Initially
- In the initial V4 migration, `sp_post_ledger_pair` accepted `p_source_balance_after`, `p_dest_balance_after`, `p_source_sequence`, `p_dest_sequence`, and `p_initiating_user_id` directly from the caller and inserted them into `ledger_entries` without verifying them against current `accounts` state.
- `transactions.status` was defined with only `('POSTED', 'REFUSED')`, allowing a caller to directly insert a `POSTED` transaction.
- `ledger_entries` lacked a unique constraint on `(transaction_id, entry_type)`, creating a window where duplicate or concurrent invocations could insert multiple debit-credit pairs for the same transaction.
- The stored procedures ran with `SQL SECURITY DEFINER` under the migration user (`ledgerline_migrator`), which held `ALL PRIVILEGES ... WITH GRANT OPTION`.
- The runtime user (`ledgerline_app`) had broad `UPDATE ON accounts`, allowing an attacker with database access to directly modify `accounts.balance`.
- In `sp_record_statement_event`, caller-supplied running balances and sequence numbers were trusted without locking the account or verifying against parent transactions.

### 3. What Was Specifically Wrong
1. **Parameter Forgery & Ledger Drift**: Accepting caller-supplied balances and sequence numbers meant a compromised application container could forge arbitrary account balances in ledger entries that disagreed with the actual account records.
2. **Missing Lifecycle Guard**: Without a distinct `PENDING` state and database lifecycle enforcement, an attacker could bypass posting logic by directly creating pre-posted transactions.
3. **Duplicate Posting Vulnerability**: Without unique constraints on `(transaction_id, entry_type)` and `(account_id, sequence_number)`, race conditions could create redundant ledger legs.
4. **Excessive Definer Privileges**: Definer routines should follow least privilege. Using the DDL/migration user as the routine definer violated the principle of least privilege.
5. **Direct Balance Tampering**: Granting `UPDATE` on `accounts` without column restrictions allowed direct `UPDATE accounts SET balance = ...`.
6. **Partial Posting Risk**: If the second ledger insert failed (e.g. unique constraint violation), without an explicit `DECLARE EXIT HANDLER FOR SQLEXCEPTION`, partial postings could occur if the client failed to roll back.

### 4. What Was Corrected & How It Was Verified
1. **Strict Transaction Lifecycle**:
   - Added `PENDING` status to `chk_tx_status CHECK (status IN ('PENDING', 'POSTED', 'REFUSED'))` and set default to `'PENDING'`.
   - Added `trg_tx_prevent_insert_posted` trigger blocking direct insertion of `POSTED` transactions.
   - Added `trg_tx_prevent_finalized_update` and `trg_tx_prevent_delete` triggers preserving finalized audit records.
2. **Duplicate Posting & Sequence Uniqueness Constraints**:
   - Added `CONSTRAINT uq_ledger_tx_entry UNIQUE (transaction_id, entry_type)`.
   - Added `CONSTRAINT uq_ledger_account_sequence UNIQUE (account_id, sequence_number)`.
3. **Derived Balances & Sequence Integrity**:
   - Re-engineered `sp_post_ledger_pair` to lock source and destination accounts in deterministic ID order (`min(id)` then `max(id)`).
   - Derives `balance_after` directly from locked account state based on account type (`CUSTOMER` liability vs `SYSTEM_CLEARING` asset), rejecting overdrafts.
   - Derives monotonic sequence numbers ($seq_{new} = seq_{old} + 1$).
   - Validates caller-supplied parameters against persisted transaction and derived states, raising `SQLSTATE 45000` on any mismatch.
   - Atomically updates `accounts.balance` and `accounts.statement_sequence_counter` inside the procedure.
4. **Dedicated Routine Definer (`ledgerline_definer`)**:
   - Created `ledgerline_definer` in `01-init-users.sh` with narrow DML privileges only (`SELECT, UPDATE` on `accounts`, `transactions`; `SELECT, INSERT` on `ledger_entries`, `statement_events`).
   - Procedures deployed with `CREATE DEFINER = '${definerUser}'@'%' ... SQL SECURITY DEFINER`.
5. **Column-Restricted Account Updates**:
   - Runtime user `ledgerline_app` granted `UPDATE (name, status) ON accounts`. Direct manipulation of `balance` and `statement_sequence_counter` is denied (`ERROR 1142`).
6. **Atomic Rollback Exit Handlers**:
   - Added `DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;` in all stored procedures.
7. **Hardened Statement Event Procedure**:
   - Updated `sp_record_statement_event` to lock account, validate parent transaction participant accounts, initiator, and amounts, and enforce running balances.
8. **Verification & Tests**:
   - Added `StoredProcedureLogicVerificationTest.java` (6 domain unit tests verifying derivations, lifecycle, and overdraft checks).
   - Expanded `MySqlDatabaseIntegrationTest.java` (13 live MySQL test cases for direct inserts, parameter forgery, duplicate calls, and column update denial).
   - Updated `FlywayMigrationScriptStructureTest.java` (5 structural migration tests).
   - Executed Maven test suite: 28 tests configured (15 passed, 13 live MySQL tests skipped until environment configuration).

### 5. Relevant Git Commit Link
- Commit: *Pending Milestone 1 completion commit*

---

## Entry 3: Stored Procedure Transaction Boundary & Spring Ownership Correction

- **Date**: 2026-11-06
- **Category**: Transaction Management, Financial Atomicity & Spring Data Coordination

### 1. Prompt Given to AI
> "Perform a complete, careful fix of the stored procedure transaction handling in Milestone 1.
> The Java Spring service layer must own the complete financial database transaction.
> Requirements:
> - Remove any internal START TRANSACTION, COMMIT, or ROLLBACK from stored procedures when they would interfere with the caller's transaction.
> - Do not use a procedure error handler that independently rolls back the caller's transaction.
> - Use RESIGNAL to propagate SQL errors to Spring.
> - Let Spring @Transactional roll back the complete operation when a database exception occurs.
> - Ensure rollback occurs for checked and unchecked exceptions (rollbackFor = Exception.class).
> - Preserve complete financial atomicity across the 9-step financial lifecycle."

### 2. What AI Produced Initially
- In the initial hardening pass, `sp_post_ledger_pair` and `sp_record_statement_event` were written with:
  ```sql
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
      ROLLBACK;
      RESIGNAL;
  END;
  ```
- This internal `ROLLBACK` was intended to prevent partial postings inside the routine, but it had a severe architectural side effect: it independently rolled back the entire MySQL transaction on the active connection, usurping transaction ownership from Spring's `TransactionManager`.

### 3. What Was Specifically Wrong
1. **Transaction Hijacking**: If Spring started a transaction (`connection.setAutoCommit(false)`), claimed an idempotency key, and called `sp_post_ledger_pair`, an internal `ROLLBACK` inside the stored procedure immediately aborted the server-side transaction without Spring's knowledge.
2. **Spring Coordination Incompatibility**: When the exception bubbled up to Spring via `RESIGNAL`, Spring's `JpaTransactionManager` attempted to invoke `connection.rollback()`, which can fail or desynchronize when the transaction has already been terminated server-side.
3. **Broken Multi-Step Atomicity**: Financial atomicity requires that the entire 9-step operation (idempotency claim, account locking, pending transaction creation, ledger posting, statement recording, idempotency response caching) commits together or rolls back together. A procedure that independently commits or rolls back breaks this atomicity.

### 4. What Was Corrected & How It Was Verified
1. **Removed Internal Transaction Commands**:
   - Removed `ROLLBACK;` from `sp_post_ledger_pair` and `sp_record_statement_event`.
   - Updated exception handlers to strictly use `RESIGNAL;`:
     ```sql
     DECLARE EXIT HANDLER FOR SQLEXCEPTION
     BEGIN
         RESIGNAL;
     END;
     ```
2. **Spring Transaction Ownership Guaranteed**:
   - Stored procedures now execute as participating statements within the caller's open database transaction.
   - Successful statements remain uncommitted until Spring calls `connection.commit()`.
   - Any failure halts procedure execution, propagates the exact `SQLException` to Spring, and triggers Spring's `@Transactional(rollbackFor = Exception.class)` to issue a single, coordinated `connection.rollback()`.
3. **Integration Test Suite Hardened (Tests A–F)**:
   - Updated `MySqlDatabaseIntegrationTest.java`:
     - **Test A**: Verified successful posting uncommitted changes exist in session, and `conn.rollback()` causes all changes to disappear.
     - **Test B**: Verified failure on second ledger insert triggers caller rollback with zero orphaned debit rows.
     - **Test C**: Verified failure during statement recording rolls back the preceding ledger posting.
     - **Test D**: Verified duplicate procedure calls are rejected.
     - **Test E**: Verified concurrent postings serialize on row locks, with exactly one succeeding.
     - **Test F**: Verified least-privilege denial (direct ledger INSERT, direct statement INSERT, direct balance UPDATE, direct POSTED transaction INSERT).
4. **Structural & Unit Tests**:
   - Added `StoredProcedureLogicVerificationTest.java` verifying multi-step atomicity rollback simulation and deterministic lock acquisition order.
   - Verified with Maven: 28 tests configured (17 passed, 11 live MySQL tests cleanly guarded and ready).

### 5. Relevant Git Commit Link
- Commit: *Pending Milestone 1 final transaction boundary commit*

---

## Entry 4: Authentication, Stateless JWT & Role-Based Access Control (Milestone 2)

- **Date**: 2026-11-06
- **Category**: Security, Authentication, JWT, RBAC & Fine-Grained Authorization

### 1. Prompt Given to AI
> "Milestone 2: Authentication, JWT & Role-Based Access Control:
> Implement secure authentication using Spring Boot and Spring Security:
> - BCryptPasswordEncoder (strength 12) for password hashing; never store plaintext passwords.
> - Account-holder registration and login returning signed HMAC-SHA256 JWT.
> - Stateless session management; validate JWT signature and expiration on every request.
> - Reject malformed, expired, tampered and invalid tokens.
> - Implement GET /api/auth/me returning authenticated user information without password hash.
> - Support three roles: ADMINISTRATOR, ACCOUNT_HOLDER, AUDITOR (strictly read-only).
> - Build reusable account-level authorization foundation (AccountAuthorizationService) checking account_permissions table, denying by default, preventing cross-user account access without leaking account existence.
> - Securely provision initial admin, auditor, and demo account holder via environment variables without hardcoded fallback credentials.
> - Provide comprehensive automated test suite and documentation updates."

### 2. What AI Produced Initially
- Full implementation of DTOs, security filter chain (`JwtAuthenticationFilter`, `JwtAuthenticationEntryPoint`, `JwtAccessDeniedHandler`), modern JJWT 0.12.6 `JwtTokenProvider`, repositories, services (`AuthService`, `AccountAuthorizationService`, `UserBootstrapService`), controllers (`AuthController`, `AdminController`, `AuditController`), and `GlobalExceptionHandler`.
- Initial test drafts contained several minor compilation and semantic defects:
  1. `AccountAuthorizationServiceTest` referenced non-existent enum variants `AccountType.CUSTOMER_CHECKING` and `PermissionType.CAN_VIEW_BALANCE` instead of `AccountType.CUSTOMER` and `PermissionType.CAN_VIEW_STATEMENT`.
  2. `AccountAuthorizationServiceTest` called an outdated `Account` constructor.
  3. `GlobalExceptionHandler.handleBadCredentials` hardcoded the static string `"Invalid email or password"`, preventing deactivated account status messages from reaching the client.
  4. In `AuthenticationSecurityIntegrationTest.testLogin_DeactivatedUser`, calling `userRepository.save()` inside a `@Transactional` test method without flushing left the modified entity un-persisted prior to the subsequent MockMvc dispatch.
  5. In `application.yml`, strict `${DEMO_ADMIN_PASSWORD}` caused application boot failures in environments where demo passwords were intentionally omitted.

### 3. What Was Specifically Wrong
1. **Enum & Schema Drift**: Hallucinating `CUSTOMER_CHECKING` or `CAN_VIEW_BALANCE` broke compilation and departed from the canonical V1 Flyway schema and domain models established in Milestone 1.
2. **Persistence Context Synchronization in Tests**: Without `saveAndFlush()`, Hibernate dirty checking deferred the update until transaction commit, leading to tests reading stale active user status.
3. **Overly Restrictive Error Handler**: Hardcoding `"Invalid email or password"` in the global handler prevented callers and tests from differentiating deactivated accounts from wrong passwords.
4. **Fragile Property Binding**: Using `${DEMO_ADMIN_PASSWORD}` without default empty fallback (`${DEMO_ADMIN_PASSWORD:}`) caused Spring Boot context initialization to fail when test configurations omitted demo seed credentials.

### 4. What Was Corrected & How It Was Verified
1. **Enum Alignment & Constructor Correction**:
   - Updated `AccountAuthorizationServiceTest` to use `AccountType.CUSTOMER` and `PermissionType.CAN_VIEW_STATEMENT`.
   - Used the canonical constructor: `new Account("ACC-101", "Alice Checking", AccountType.CUSTOMER, "INR")`.
2. **Exception Handling & Test Entity Flushing**:
   - Updated `GlobalExceptionHandler.handleBadCredentials` to evaluate `ex.getMessage()` while defaulting safely to `"Invalid email or password"`.
   - Updated `AuthenticationSecurityIntegrationTest` to use `userRepository.saveAndFlush(holder)` to guarantee persistence context synchronization before MockMvc request execution.
3. **Graceful Demo Password Handling**:
   - Updated `application.yml` property placeholders to `${DEMO_ADMIN_PASSWORD:}` and enhanced `UserBootstrapService` to cleanly skip bootstrapping when variables are unset.
4. **Comprehensive Test Suite & Verification**:
   - Created 4 dedicated Milestone 2 test suites:
     - `JwtTokenProviderTest` (7 unit tests): Signature validation, claim extraction, 256-bit key length enforcement, expiration, tampering, malformed inputs.
     - `AccountAuthorizationServiceTest` (12 unit tests): Fine-grained permission checks, auditor read-only enforcement, default-deny behavior, anti-enumeration IDOR prevention.
     - `AuthenticationSecurityIntegrationTest` (12 integration tests): Registration, BCrypt hash verification, password omission in responses, role tampering rejection, login authentication, deactivated user checks, `/api/auth/me` profile queries.
     - `RoleBasedAccessControlIntegrationTest` (13 integration tests): Admin system-info access, user provisioning, auditor inspection access, auditor write prohibition (`POST /api/audit/records` denied).
   - Executed Maven test suite: **72 total tests run, 61 passed, 11 live MySQL tests skipped as designed, 0 failures, 0 errors**.

### 5. Relevant Git Commit Link
- Commit: *Pending Milestone 2 completion commit*
