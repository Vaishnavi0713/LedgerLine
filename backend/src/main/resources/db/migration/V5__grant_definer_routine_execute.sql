-- ============================================================================
-- LedgerLine Migration V5: Grant Procedure EXECUTE Privileges to Routine Definer
-- ============================================================================
-- Under MySQL's SQL SECURITY DEFINER execution model, MySQL switches the execution
-- context to the routine's DEFINER account ('${definerUser}'@'%'). Before executing
-- the routine body, MySQL requires that the DEFINER account itself holds the EXECUTE
-- privilege on the routine.
--
-- Migration V4 granted EXECUTE to '${appUser}'@'%', but omitted '${definerUser}'@'%'.
-- This migration grants explicit EXECUTE privileges on the two financial write boundary
-- procedures to the definer user.
-- ============================================================================

GRANT EXECUTE ON PROCEDURE sp_post_ledger_pair TO '${definerUser}'@'%';
GRANT EXECUTE ON PROCEDURE sp_record_statement_event TO '${definerUser}'@'%';
