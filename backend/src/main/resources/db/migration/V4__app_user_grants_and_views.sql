-- ==============================================================================
-- LedgerLine — V4 Hardened Write Boundary Procedures, Views & Least Privilege
-- ==============================================================================
-- 1. Creates continuous imbalance detection view for auditors.
-- 2. Deploys sp_post_ledger_pair to derive balances/sequences, enforce double-entry
--    equality, lock rows deterministically, transition transaction lifecycle,
--    and propagate errors via RESIGNAL without independently committing or rolling back.
-- 3. Deploys sp_record_statement_event to validate statement timelines,
--    enforce monotonic sequences, verify parent transaction consistency, and prevent duplicates.
-- 4. Uses dedicated routine definer ('${definerUser}') with least-privilege DML.
-- 5. Restricts runtime user ('${appUser}') from directly updating balances,
--    inserting arbitrary POSTED transactions, or writing to ledger/statement tables.
-- ==============================================================================

-- 1. Continuous audit view: surfaces any posted transaction where sum(debits) != sum(credits)
CREATE OR REPLACE VIEW v_unbalanced_transactions AS
SELECT 
    transaction_id,
    COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0.00 END), 0.00) AS total_debits,
    COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0.00 END), 0.00) AS total_credits,
    COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0.00) AS net_imbalance
FROM ledger_entries
GROUP BY transaction_id
HAVING net_imbalance != 0.00;

-- 2. Definer Grants: Grant dedicated definer narrow DML permissions required for stored procedures
GRANT SELECT, UPDATE ON accounts TO '${definerUser}'@'%';
GRANT SELECT, UPDATE ON transactions TO '${definerUser}'@'%';
GRANT SELECT, INSERT ON ledger_entries TO '${definerUser}'@'%';
GRANT SELECT, INSERT ON statement_events TO '${definerUser}'@'%';

-- 3. Stored Procedures Idempotency: Drop existing procedures if present (ensures repeatable migration on retry)
DROP PROCEDURE IF EXISTS sp_post_ledger_pair;
DROP PROCEDURE IF EXISTS sp_record_statement_event;

-- 4. Strictly balanced, hardened ledger posting procedure
DELIMITER //

CREATE DEFINER = '${definerUser}'@'%' PROCEDURE sp_post_ledger_pair(
    IN p_transaction_id VARCHAR(36),
    IN p_initiating_user_id BIGINT,
    IN p_source_account_id BIGINT,
    IN p_dest_account_id BIGINT,
    IN p_amount DECIMAL(19, 2),
    IN p_source_balance_after DECIMAL(19, 2),
    IN p_dest_balance_after DECIMAL(19, 2),
    IN p_source_sequence BIGINT,
    IN p_dest_sequence BIGINT
)
SQL SECURITY DEFINER
BEGIN
    -- 1. All local variable declarations must precede handler declarations in MySQL
    DECLARE v_tx_status VARCHAR(20);
    DECLARE v_tx_amount DECIMAL(19, 2);
    DECLARE v_tx_source_id BIGINT;
    DECLARE v_tx_dest_id BIGINT;
    DECLARE v_tx_initiating_user_id BIGINT;
    DECLARE v_tx_type VARCHAR(20);

    DECLARE v_first_lock_id BIGINT;
    DECLARE v_second_lock_id BIGINT;

    DECLARE v_src_curr_balance DECIMAL(19, 2);
    DECLARE v_src_curr_seq BIGINT;
    DECLARE v_src_type VARCHAR(20);
    DECLARE v_src_status VARCHAR(20);
    DECLARE v_src_derived_balance DECIMAL(19, 2);
    DECLARE v_src_derived_seq BIGINT;

    DECLARE v_dest_curr_balance DECIMAL(19, 2);
    DECLARE v_dest_curr_seq BIGINT;
    DECLARE v_dest_type VARCHAR(20);
    DECLARE v_dest_status VARCHAR(20);
    DECLARE v_dest_derived_balance DECIMAL(19, 2);
    DECLARE v_dest_derived_seq BIGINT;

    -- 2. Error handler: Propagates exceptions directly to the caller without independently rolling back or committing
    -- MySQL requires handlers to be declared after all variable and condition declarations
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        RESIGNAL;
    END;

    -- 1. Fetch and lock the parent transaction record
    SELECT status, amount, source_account_id, destination_account_id, initiating_user_id, type
    INTO v_tx_status, v_tx_amount, v_tx_source_id, v_tx_dest_id, v_tx_initiating_user_id, v_tx_type
    FROM transactions
    WHERE id = p_transaction_id
    FOR UPDATE;

    IF v_tx_status IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Transaction record does not exist.';
    END IF;

    -- 2. Enforce strict transaction lifecycle
    IF v_tx_status = 'POSTED' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Transaction is already POSTED (duplicate posting rejected).';
    END IF;

    IF v_tx_status != 'PENDING' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Transaction must be in PENDING status to post.';
    END IF;

    -- 3. Validate transaction type
    IF v_tx_type NOT IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL', 'ADJUSTMENT') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Invalid transaction type.';
    END IF;

    -- 4. Duplicate posting protection: verify no ledger entries already exist
    IF EXISTS (SELECT 1 FROM ledger_entries WHERE transaction_id = p_transaction_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Ledger entries already exist for this transaction.';
    END IF;

    -- 5. Validate positive amount
    IF v_tx_amount <= 0.00 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Transaction amount must be strictly positive.';
    END IF;

    -- 6. Validate caller inputs against persisted transaction (prevent parameter forgery)
    IF p_initiating_user_id IS NOT NULL AND p_initiating_user_id != v_tx_initiating_user_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Caller initiating user ID does not match persisted transaction.';
    END IF;

    IF p_source_account_id IS NOT NULL AND p_source_account_id != v_tx_source_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Caller source account ID does not match persisted transaction.';
    END IF;

    IF p_dest_account_id IS NOT NULL AND p_dest_account_id != v_tx_dest_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Caller destination account ID does not match persisted transaction.';
    END IF;

    IF p_amount IS NOT NULL AND p_amount != v_tx_amount THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Caller amount does not match persisted transaction.';
    END IF;

    IF v_tx_source_id = v_tx_dest_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Source and destination accounts cannot be identical.';
    END IF;

    -- 7. Deterministic lock acquisition order to eliminate deadlocks (min ID, then max ID)
    IF v_tx_source_id < v_tx_dest_id THEN
        SET v_first_lock_id = v_tx_source_id;
        SET v_second_lock_id = v_tx_dest_id;
    ELSE
        SET v_first_lock_id = v_tx_dest_id;
        SET v_second_lock_id = v_tx_source_id;
    END IF;

    SELECT id FROM accounts WHERE id = v_first_lock_id FOR UPDATE;
    SELECT id FROM accounts WHERE id = v_second_lock_id FOR UPDATE;

    -- 8. Fetch locked account states
    SELECT balance, statement_sequence_counter, account_type, status
    INTO v_src_curr_balance, v_src_curr_seq, v_src_type, v_src_status
    FROM accounts
    WHERE id = v_tx_source_id;

    SELECT balance, statement_sequence_counter, account_type, status
    INTO v_dest_curr_balance, v_dest_curr_seq, v_dest_type, v_dest_status
    FROM accounts
    WHERE id = v_tx_dest_id;

    IF v_src_status != 'ACTIVE' OR v_dest_status != 'ACTIVE' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Both accounts must be ACTIVE to post transaction.';
    END IF;

    -- 9. Derive resulting balances based on account types
    -- Source account (DEBIT leg)
    IF v_src_type = 'CUSTOMER' THEN
        IF v_src_curr_balance < v_tx_amount THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Insufficient balance in source customer account.';
        END IF;
        SET v_src_derived_balance = v_src_curr_balance - v_tx_amount;
    ELSE -- SYSTEM_CLEARING (Asset increases on external deposit)
        SET v_src_derived_balance = v_src_curr_balance + v_tx_amount;
    END IF;

    -- Destination account (CREDIT leg)
    IF v_dest_type = 'CUSTOMER' THEN
        SET v_dest_derived_balance = v_dest_curr_balance + v_tx_amount;
    ELSE -- SYSTEM_CLEARING (Asset decreases on external withdrawal)
        IF v_dest_curr_balance < v_tx_amount THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Insufficient clearing balance for disbursement.';
        END IF;
        SET v_dest_derived_balance = v_dest_curr_balance - v_tx_amount;
    END IF;

    -- 10. Derive monotonic sequence numbers
    SET v_src_derived_seq = v_src_curr_seq + 1;
    SET v_dest_derived_seq = v_dest_curr_seq + 1;

    -- 11. Validate caller expectations if provided (prevent forged balances / sequences)
    IF p_source_balance_after IS NOT NULL AND p_source_balance_after != v_src_derived_balance THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Caller source balance_after does not match persisted derivation.';
    END IF;

    IF p_dest_balance_after IS NOT NULL AND p_dest_balance_after != v_dest_derived_balance THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Caller destination balance_after does not match persisted derivation.';
    END IF;

    IF p_source_sequence IS NOT NULL AND p_source_sequence != v_src_derived_seq THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Caller source sequence_number does not match monotonic sequence.';
    END IF;

    IF p_dest_sequence IS NOT NULL AND p_dest_sequence != v_dest_derived_seq THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Caller destination sequence_number does not match monotonic sequence.';
    END IF;

    -- 12. Update persisted accounts atomically
    UPDATE accounts
    SET balance = v_src_derived_balance, statement_sequence_counter = v_src_derived_seq
    WHERE id = v_tx_source_id;

    UPDATE accounts
    SET balance = v_dest_derived_balance, statement_sequence_counter = v_dest_derived_seq
    WHERE id = v_tx_dest_id;

    -- 13. Atomically insert balanced DEBIT and CREDIT ledger entries
    INSERT INTO ledger_entries (
        transaction_id, account_id, initiating_user_id, entry_type, amount, balance_after, sequence_number
    ) VALUES (
        p_transaction_id, v_tx_source_id, v_tx_initiating_user_id, 'DEBIT', v_tx_amount, v_src_derived_balance, v_src_derived_seq
    );

    INSERT INTO ledger_entries (
        transaction_id, account_id, initiating_user_id, entry_type, amount, balance_after, sequence_number
    ) VALUES (
        p_transaction_id, v_tx_dest_id, v_tx_initiating_user_id, 'CREDIT', v_tx_amount, v_dest_derived_balance, v_dest_derived_seq
    );

    -- 14. Transition transaction status to POSTED
    UPDATE transactions
    SET status = 'POSTED', completed_at = CURRENT_TIMESTAMP
    WHERE id = p_transaction_id;
END //

CREATE DEFINER = '${definerUser}'@'%' PROCEDURE sp_record_statement_event(
    IN p_account_id BIGINT,
    IN p_sequence_number BIGINT,
    IN p_initiating_user_id BIGINT,
    IN p_transaction_id VARCHAR(36),
    IN p_event_type VARCHAR(30),
    IN p_amount DECIMAL(19, 2),
    IN p_running_balance DECIMAL(19, 2),
    IN p_status VARCHAR(20),
    IN p_reason VARCHAR(255)
)
SQL SECURITY DEFINER
BEGIN
    -- 1. All local variable declarations must precede handler declarations in MySQL
    DECLARE v_acc_balance DECIMAL(19, 2);
    DECLARE v_acc_seq BIGINT;
    DECLARE v_acc_status VARCHAR(20);

    DECLARE v_tx_initiator BIGINT;
    DECLARE v_tx_source BIGINT;
    DECLARE v_tx_dest BIGINT;
    DECLARE v_tx_amount DECIMAL(19, 2);
    DECLARE v_tx_status VARCHAR(20);

    DECLARE v_effective_seq BIGINT;
    DECLARE v_effective_initiator BIGINT;

    -- 2. Error handler: Propagates exceptions directly to the caller without independently rolling back or committing
    -- MySQL requires handlers to be declared after all variable and condition declarations
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        RESIGNAL;
    END;

    -- 1. Validate positive amount
    IF p_amount <= 0.00 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Statement event amount must be positive.';
    END IF;

    -- 2. Lock and validate target account
    SELECT balance, statement_sequence_counter, status
    INTO v_acc_balance, v_acc_seq, v_acc_status
    FROM accounts
    WHERE id = p_account_id
    FOR UPDATE;

    IF v_acc_status IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Account does not exist.';
    END IF;

    -- 3. If parent transaction is provided, validate consistency
    IF p_transaction_id IS NOT NULL THEN
        SELECT initiating_user_id, source_account_id, destination_account_id, amount, status
        INTO v_tx_initiator, v_tx_source, v_tx_dest, v_tx_amount, v_tx_status
        FROM transactions
        WHERE id = p_transaction_id
        FOR UPDATE;

        IF v_tx_status IS NULL THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Parent transaction does not exist.';
        END IF;

        IF p_account_id != v_tx_source AND p_account_id != v_tx_dest THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Account is not a participant in parent transaction.';
        END IF;

        IF p_amount != v_tx_amount THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Statement amount does not match parent transaction amount.';
        END IF;

        IF p_status != v_tx_status THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Statement status does not match parent transaction status.';
        END IF;

        IF p_initiating_user_id IS NOT NULL AND p_initiating_user_id != v_tx_initiator THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Initiating user ID does not match parent transaction.';
        END IF;

        -- Prevent duplicate statement event for same account and transaction
        IF EXISTS (SELECT 1 FROM statement_events WHERE account_id = p_account_id AND transaction_id = p_transaction_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Statement event already exists for this account and transaction.';
        END IF;

        SET v_effective_initiator = v_tx_initiator;
    ELSE
        IF p_initiating_user_id IS NULL THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Initiating user ID must be provided.';
        END IF;
        SET v_effective_initiator = p_initiating_user_id;
    END IF;

    -- 4. Derive and validate running balance against persisted account balance
    IF p_running_balance IS NOT NULL AND p_running_balance != v_acc_balance THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Caller running balance does not match persisted account balance.';
    END IF;

    -- 5. Sequence number derivation and validation
    -- For REFUSED events: account balance is untouched, but statement sequence advances
    IF p_status = 'REFUSED' THEN
        SET v_effective_seq = v_acc_seq + 1;
        UPDATE accounts
        SET statement_sequence_counter = v_effective_seq
        WHERE id = p_account_id;
    ELSE
        -- For POSTED events: statement_sequence_counter was already advanced during sp_post_ledger_pair
        SET v_effective_seq = v_acc_seq;
    END IF;

    IF p_sequence_number IS NOT NULL AND p_sequence_number != v_effective_seq THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Caller sequence number does not match account sequence.';
    END IF;

    -- 6. Insert into statement_events
    INSERT INTO statement_events (
        account_id, sequence_number, initiating_user_id, transaction_id,
        event_type, amount, running_balance, status, reason
    ) VALUES (
        p_account_id, v_effective_seq, v_effective_initiator, p_transaction_id,
        p_event_type, p_amount, v_acc_balance, p_status, p_reason
    );
END //

DELIMITER ;

-- 5. Operational Grants: Allow application to create transactions, users, permissions, idempotency keys, and audit logs
-- Restrict accounts UPDATE to non-balance columns (name, status) to prevent direct balance manipulation
GRANT INSERT ON accounts TO '${appUser}'@'%';
GRANT UPDATE (name, status) ON accounts TO '${appUser}'@'%';

GRANT INSERT, UPDATE ON transactions TO '${appUser}'@'%';
GRANT INSERT, UPDATE ON idempotency_keys TO '${appUser}'@'%';
GRANT INSERT, UPDATE ON users TO '${appUser}'@'%';
GRANT INSERT, UPDATE ON account_permissions TO '${appUser}'@'%';
GRANT INSERT ON audit_records TO '${appUser}'@'%';

-- 6. Stored Procedure Grants: Allow application to invoke write boundaries
GRANT EXECUTE ON PROCEDURE sp_post_ledger_pair TO '${appUser}'@'%';
GRANT EXECUTE ON PROCEDURE sp_record_statement_event TO '${appUser}'@'%';

