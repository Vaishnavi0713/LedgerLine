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
