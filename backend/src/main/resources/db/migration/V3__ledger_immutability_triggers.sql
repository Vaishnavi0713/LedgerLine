-- ==============================================================================
-- LedgerLine — V3 Ledger & Statement Immutability Triggers
-- ==============================================================================
-- Enforces append-only immutability directly at the database engine level.
-- Any attempt to UPDATE or DELETE records from ledger_entries or statement_events
-- raises SQLSTATE 45000 and immediately aborts the transaction.
-- ==============================================================================

DELIMITER //

CREATE TRIGGER trg_ledger_prevent_update
BEFORE UPDATE ON ledger_entries
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: ledger_entries is strictly append-only. Updates are prohibited.';
END //

CREATE TRIGGER trg_ledger_prevent_delete
BEFORE DELETE ON ledger_entries
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: ledger_entries is strictly append-only. Deletions are prohibited.';
END //

CREATE TRIGGER trg_stmt_prevent_update
BEFORE UPDATE ON statement_events
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: statement_events is strictly append-only. Updates are prohibited.';
END //

CREATE TRIGGER trg_stmt_prevent_delete
BEFORE DELETE ON statement_events
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: statement_events is strictly append-only. Deletions are prohibited.';
END //

-- Transaction Lifecycle & Immutability Triggers
-- Prevents creating transactions already marked POSTED (bypassing posting procedure)
CREATE TRIGGER trg_tx_prevent_insert_posted
BEFORE INSERT ON transactions
FOR EACH ROW
BEGIN
    IF NEW.status = 'POSTED' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Transactions cannot be created directly in POSTED status. Lifecycle must begin as PENDING or REFUSED.';
    END IF;
END //

-- Prevents modifying finalized transactions (POSTED / REFUSED)
CREATE TRIGGER trg_tx_prevent_finalized_update
BEFORE UPDATE ON transactions
FOR EACH ROW
BEGIN
    IF OLD.status IN ('POSTED', 'REFUSED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Finalized transactions (POSTED or REFUSED) cannot be modified.';
    END IF;
END //

-- Prevents deleting financial transactions
CREATE TRIGGER trg_tx_prevent_delete
BEFORE DELETE ON transactions
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIOLATION: Financial transactions are permanent audit records and cannot be deleted.';
END //

DELIMITER ;
