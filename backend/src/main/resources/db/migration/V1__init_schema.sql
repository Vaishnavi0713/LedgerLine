-- ==============================================================================
-- LedgerLine — V1 Initial Schema Migration
-- ==============================================================================
-- Currency: Strictly Indian Rupees (INR)
-- Precision: DECIMAL(19, 2)
-- Engine: InnoDB with strict foreign keys and transactional integrity
-- ==============================================================================

-- 1. Users Table
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_role CHECK (role IN ('ADMINISTRATOR', 'ACCOUNT_HOLDER', 'AUDITOR')),
    INDEX idx_users_username (username),
    INDEX idx_users_role (role)
) ENGINE=InnoDB;

-- 2. Accounts Table
-- Holds Customer liability accounts and the dedicated SYSTEM_CLEARING asset account.
CREATE TABLE accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_number VARCHAR(36) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    account_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    balance DECIMAL(19, 2) NOT NULL DEFAULT 0.00,
    statement_sequence_counter BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_account_type CHECK (account_type IN ('CUSTOMER', 'SYSTEM_CLEARING')),
    CONSTRAINT chk_account_currency CHECK (currency = 'INR'),
    CONSTRAINT chk_account_balance CHECK (balance >= 0.00),
    CONSTRAINT chk_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    INDEX idx_accounts_num (account_number),
    INDEX idx_accounts_type (account_type)
) ENGINE=InnoDB;

-- 3. Account Permissions Table
-- Granular per-account authorization (Rule 9).
CREATE TABLE account_permissions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    permission VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_perm_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_perm_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT chk_perm_type CHECK (permission IN ('CAN_DEPOSIT', 'CAN_WITHDRAW', 'CAN_TRANSFER', 'CAN_VIEW_STATEMENT', 'CAN_ADJUST')),
    CONSTRAINT uq_user_acc_perm UNIQUE (user_id, account_id, permission)
) ENGINE=InnoDB;

-- 4. Transactions Table
-- Reversal protection: reversal_target_id generated column ensures a posted transaction cannot be reversed twice.
CREATE TABLE transactions (
    id VARCHAR(36) PRIMARY KEY,
    reference_id VARCHAR(64) NOT NULL UNIQUE,
    initiating_user_id BIGINT NOT NULL,
    source_account_id BIGINT NOT NULL,
    destination_account_id BIGINT NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    refusal_reason VARCHAR(255) NULL,
    description VARCHAR(255) NULL,
    original_transaction_id VARCHAR(36) NULL,
    reversal_target_id VARCHAR(36) GENERATED ALWAYS AS (
        CASE 
            WHEN type = 'REVERSAL' AND status = 'POSTED' THEN original_transaction_id 
            ELSE NULL 
        END
    ) STORED,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    CONSTRAINT chk_tx_amount CHECK (amount > 0.00),
    CONSTRAINT chk_tx_currency CHECK (currency = 'INR'),
    CONSTRAINT chk_tx_type CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL', 'ADJUSTMENT')),
    CONSTRAINT chk_tx_status CHECK (status IN ('PENDING', 'POSTED', 'REFUSED')),
    CONSTRAINT fk_tx_user FOREIGN KEY (initiating_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tx_src FOREIGN KEY (source_account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tx_dest FOREIGN KEY (destination_account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tx_original FOREIGN KEY (original_transaction_id) REFERENCES transactions(id) ON DELETE RESTRICT,
    CONSTRAINT uq_single_successful_reversal UNIQUE (reversal_target_id),
    INDEX idx_tx_src (source_account_id),
    INDEX idx_tx_dest (destination_account_id),
    INDEX idx_tx_status (status),
    INDEX idx_tx_created (created_at)
) ENGINE=InnoDB;

-- 5. Ledger Entries Table (Strictly Append-Only)
-- Double-entry records: every posted transaction creates balanced DEBIT and CREDIT records.
-- uq_ledger_tx_entry guarantees a transaction can have at most one DEBIT and one CREDIT.
-- uq_ledger_account_sequence guarantees strictly unique sequence numbers per account.
CREATE TABLE ledger_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL,
    account_id BIGINT NOT NULL,
    initiating_user_id BIGINT NOT NULL,
    entry_type VARCHAR(10) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    balance_after DECIMAL(19, 2) NOT NULL,
    sequence_number BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_amount CHECK (amount > 0.00),
    CONSTRAINT chk_ledger_balance_after CHECK (balance_after >= 0.00),
    CONSTRAINT fk_ledger_tx FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_acc FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_user FOREIGN KEY (initiating_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT uq_ledger_tx_entry UNIQUE (transaction_id, entry_type),
    CONSTRAINT uq_ledger_account_sequence UNIQUE (account_id, sequence_number),
    INDEX idx_ledger_acc_seq (account_id, sequence_number),
    INDEX idx_ledger_tx (transaction_id),
    INDEX idx_ledger_created (created_at)
) ENGINE=InnoDB;

-- 6. Statement Events Table (Strictly Append-Only)
-- Customer-facing statement history with monotonic sequence numbers and refused attempts.
CREATE TABLE statement_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    sequence_number BIGINT NOT NULL,
    initiating_user_id BIGINT NOT NULL,
    transaction_id VARCHAR(36) NULL,
    event_type VARCHAR(30) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    running_balance DECIMAL(19, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_stmt_event_type CHECK (event_type IN ('POSTED_DEBIT', 'POSTED_CREDIT', 'REFUSED_WITHDRAWAL', 'REFUSED_TRANSFER', 'REFUSED_DEPOSIT')),
    CONSTRAINT chk_stmt_status CHECK (status IN ('POSTED', 'REFUSED')),
    CONSTRAINT chk_stmt_amount CHECK (amount > 0.00),
    CONSTRAINT chk_stmt_running_balance CHECK (running_balance >= 0.00),
    CONSTRAINT fk_stmt_acc FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_stmt_user FOREIGN KEY (initiating_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_stmt_tx FOREIGN KEY (transaction_id) REFERENCES transactions(id) ON DELETE RESTRICT,
    CONSTRAINT uq_account_sequence UNIQUE (account_id, sequence_number),
    INDEX idx_stmt_acc_seq (account_id, sequence_number),
    INDEX idx_stmt_acc_created (account_id, created_at)
) ENGINE=InnoDB;

-- 7. Idempotency Keys Table
-- Uniqueness scoped by (user_id, operation_type, idempotency_key).
CREATE TABLE idempotency_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_status INT NULL,
    response_body TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_idem_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT fk_idem_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_user_op_key UNIQUE (user_id, operation_type, idempotency_key),
    INDEX idx_idem_expires (expires_at)
) ENGINE=InnoDB;

-- 8. Audit Records Table
-- System-wide immutable security and audit trail.
CREATE TABLE audit_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    user_agent VARCHAR(255) NULL,
    status VARCHAR(20) NOT NULL,
    metadata JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_audit_status CHECK (status IN ('SUCCESS', 'DENIED', 'FAILED')),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_audit_user (user_id),
    INDEX idx_audit_action (action),
    INDEX idx_audit_created (created_at)
) ENGINE=InnoDB;
