# LedgerLine — Money Movement with a Ledger You Can Prove

LedgerLine is a secure, double-entry financial web application designed to demonstrate mathematically provable money movement, zero overdrafts, idempotency, concurrency safety, and tamper-resistant auditability.

---

## Core Engineering Principles

1. **Balanced Double-Entry Accounting**: Every financial event consists of balanced Debit and Credit entries ($\sum \text{Debits} - \sum \text{Credits} = 0$). External cash movements are cleared through a dedicated `SYSTEM_CLEARING` account.
2. **Exact Monetary Representation**: All balances and transactions are denominated strictly in Indian Rupees (**`INR`**) using `DECIMAL(19, 2)` in MySQL and `BigDecimal` in Java. Requests with $>2$ decimal places or non-positive amounts are rejected. No silent rounding.
3. **Zero Overdraft Policy**: Account balances can never drop below zero ($\text{balance} \ge 0.00$). Enforced via application pre-checks, pessimistic row locking, atomic conditional SQL updates, and database `CHECK` constraints.
4. **Single-Transaction Atomic Idempotency**: All mutating operations require a client-supplied `Idempotency-Key`. The idempotency record, financial balance updates, double-entry ledger postings, and customer statement events are coordinated within a single atomic MySQL transaction.
5. **Deterministic Concurrency & Deadlock Elimination**: Cross-account transfers acquire row-level locks strictly in ascending order of primary key (`min(id)` then `max(id)`). Retries execute inside fresh transactions via Spring AOP aspect ordering (`@Retryable` wrapping `@Transactional`).
6. **Chronological Statement Integrity**: Every debit, credit, and refused attempt is recorded on account statements with an unambiguous, monotonic `sequence_number` per account and the initiating user ID.
7. **Privilege Separation & Append-Only Ledger**: Operational ledger and statement tables are protected by MySQL engine triggers (`BEFORE UPDATE`, `BEFORE DELETE`) raising SQLSTATE `45000`. The application runtime user (`ledgerline_app`) is strictly barred from DDL and destructive operations.

---

## Roles & Access Control

The application implements three distinct roles:
- **`ADMINISTRATOR`**: System administration, user management, and operational monitoring. Cannot silently edit or delete ledger entries.
- **`ACCOUNT_HOLDER`**: Manages personal accounts, initiates deposits, withdrawals, and peer-to-peer transfers within authorized limits, and views statements.
- **`AUDITOR`**: Strictly **Read-Only** access across all accounts, ledger records, statement events, and security logs. Write operations by auditors are rejected with `403 Forbidden`.

---

## Documentation Links

- **Architecture Specification**: [ARCHITECTURE.md](docs/ARCHITECTURE.md)
- **Threat Model & Security Countermeasures**: [THREATS.md](docs/THREATS.md)
- **AI Development Log & Mistake Analysis**: [PROMPTS.md](docs/PROMPTS.md)
- **REST API Contracts & Idempotency Rules**: [API.md](docs/API.md)

---

## Prerequisites

- **Java**: JDK 17 LTS or higher
- **Maven**: 3.9+
- **Node.js**: v20+ LTS (with npm)
- **Database**: MySQL 8.0 (or Docker Engine / Docker Desktop)

---

## Clean-Clone Setup & Execution Instructions

### Step 1: Clone and Configure Environment

```bash
# 1. Clone repository
git clone https://github.com/<your-username>/LedgerLine.git
cd LedgerLine

# 2. Copy the environment configuration template
cp .env.example .env
```

Open `.env` in your editor and provide secure values for all required variables:
- **Database Passwords**: `DB_ROOT_PASSWORD`, `DB_MIGRATOR_PASSWORD`, and `DB_APP_PASSWORD`.
- **JWT Secret**: `JWT_SECRET` (generate a 256-bit secure key).
- **Demo Passwords**: `DEMO_ADMIN_PASSWORD`, `DEMO_HOLDER_PASSWORD`, and `DEMO_AUDITOR_PASSWORD`.

> [!IMPORTANT]
> Zero Default Secrets Policy: Default or fallback passwords are not permitted in source control or configuration files. If any required credential is missing from `.env`, Docker Compose and the backend application will fail-fast with a configuration error.

### Step 2: Start the MySQL Database

#### Option A: Using Docker Compose (Recommended)
```bash
docker compose up -d mysql
```
- Docker Compose loads `.env` and passes credentials into the MySQL container environment.
- On first boot, the official MySQL container executes `database/init/01-init-users.sh` via `/docker-entrypoint-initdb.d/`.
- The initialization script dynamically creates the database and configures the two separate database users (`ledgerline_migrator` for Flyway DDL and `ledgerline_app` for application DML) using your environment credentials without logging passwords.

#### Option B: Using an Existing Local MySQL Server
If using a standalone local MySQL 8.0 instance instead of Docker, execute the following commands in your MySQL shell (substituting your chosen passwords from `.env`):
```sql
CREATE DATABASE IF NOT EXISTS ledgerline_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1. Migration User (Flyway DDL)
CREATE USER IF NOT EXISTS 'ledgerline_migrator'@'%' IDENTIFIED BY 'YOUR_CHOSEN_MIGRATOR_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, INDEX, REFERENCES, TRIGGER 
    ON ledgerline_db.* TO 'ledgerline_migrator'@'%';

-- 2. Application Runtime User (Least Privilege: DML only)
CREATE USER IF NOT EXISTS 'ledgerline_app'@'%' IDENTIFIED BY 'YOUR_CHOSEN_APP_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON ledgerline_db.* TO 'ledgerline_app'@'%';

FLUSH PRIVILEGES;
```

### Step 3: Build and Run the Backend

```bash
cd backend
mvn clean package
java -jar target/ledgerline-backend-0.0.1-SNAPSHOT.jar
```
The backend initializes database tables via Flyway migrations (as `ledgerline_migrator`), seeds the demo accounts using your `.env` passwords (as `ledgerline_app`), and listens on port `8080`.

### Step 4: Run the Frontend Application

In a separate terminal:
```bash
cd frontend
npm install
npm run dev
```
Open `http://localhost:5173` in your browser.

---

## Seed Demo Credentials for Evaluation

When you start the application with your `.env` configuration, the following accounts are initialized:

| Role | Username / Email | Initial Password Source | Permissions |
| :--- | :--- | :--- | :--- |
| **Administrator** | `admin@ledgerline.test` | Configured in `DEMO_ADMIN_PASSWORD` | User management, system health, read/audit |
| **Account Holder** | `holder@ledgerline.test` | Configured in `DEMO_HOLDER_PASSWORD` | Account owner, deposit, withdraw, transfer |
| **Auditor** | `auditor@ledgerline.test` | Configured in `DEMO_AUDITOR_PASSWORD` | Strictly Read-Only access to ledger and statements |

---

## Verification & Testing

To execute the automated regression test suite:
```bash
cd backend
mvn test
```

The test suite covers:
- **Authentication & Security (`AuthenticationSecurityIntegrationTest`)**: Registration, BCrypt password hashing (cost factor 12), payload role-tampering defense, password exclusion from API responses, login JWT issuance, deactivated account rejection, `/api/auth/me` profile resolution.
- **Stateless JWT Provider (`JwtTokenProviderTest`)**: HMAC-SHA256 signing, claims extraction, 256-bit key length validation, expiration, signature verification, tampering detection.
- **Role-Based Access Control (`RoleBasedAccessControlIntegrationTest`)**: RBAC enforcement across `ADMINISTRATOR`, `ACCOUNT_HOLDER`, and `AUDITOR` roles, admin user provisioning, auditor inspection, auditor write prohibition (`POST /api/audit/records` denied with `403 Forbidden`).
- **Account-Level Authorization (`AccountAuthorizationServiceTest`)**: Reusable authorization foundation, fine-grained permission checks (`account_permissions`), default-deny semantics, auditor read-only enforcement, anti-enumeration IDOR prevention.
- **Mathematical Money Conservation (`MoneyPrecisionAndConservationTest`)**: Exact INR scale 2 precision, rejection of $>2$ decimals and non-positive values, global conservation proofs.
- **Stored Procedure Logic & Atomicity (`StoredProcedureLogicVerificationTest`)**: Multi-step transaction atomicity simulation, account derivation, deterministic lock acquisition ordering.
- **Database Schema & Migrations (`FlywayMigrationScriptStructureTest`, `JpaEntityMappingTest`)**: Verification of Flyway migration integrity and JPA entity bindings.
- **Live MySQL Integration (`MySqlDatabaseIntegrationTest`)**: Guarded by `RUN_MYSQL_TESTS=true` for live database verification against MySQL 8.0.

> [!NOTE]
> When executing `mvn test` without a running external MySQL container, live database tests are cleanly skipped (`11 skipped`), while all 61 in-memory and MockMvc tests execute and pass (`61 passed, 0 failures, 0 errors`).
