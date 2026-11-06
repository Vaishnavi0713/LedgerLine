package com.ledgerline.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlywayMigrationScriptStructureTest {

    private static final Path MIGRATIONS_DIR = Paths.get("src/main/resources/db/migration");

    @Test
    @DisplayName("Verify that all expected Flyway migration files exist")
    void testMigrationFilesExist() {
        assertTrue(Files.exists(MIGRATIONS_DIR.resolve("V1__init_schema.sql")), "V1__init_schema.sql must exist");
        assertTrue(Files.exists(MIGRATIONS_DIR.resolve("V2__seed_system_accounts.sql")), "V2__seed_system_accounts.sql must exist");
        assertTrue(Files.exists(MIGRATIONS_DIR.resolve("V3__ledger_immutability_triggers.sql")), "V3__ledger_immutability_triggers.sql must exist");
        assertTrue(Files.exists(MIGRATIONS_DIR.resolve("V4__app_user_grants_and_views.sql")), "V4__app_user_grants_and_views.sql must exist");
        assertTrue(Files.exists(MIGRATIONS_DIR.resolve("V5__grant_definer_routine_execute.sql")), "V5__grant_definer_routine_execute.sql must exist");
    }

    @Test
    @DisplayName("V1 Schema: Verify all 8 mandatory tables are defined with required columns and constraints")
    void testV1SchemaContainsMandatoryTables() throws IOException {
        String v1Content = Files.readString(MIGRATIONS_DIR.resolve("V1__init_schema.sql"));

        List<String> requiredTables = List.of(
            "CREATE TABLE users",
            "CREATE TABLE accounts",
            "CREATE TABLE account_permissions",
            "CREATE TABLE transactions",
            "CREATE TABLE ledger_entries",
            "CREATE TABLE statement_events",
            "CREATE TABLE idempotency_keys",
            "CREATE TABLE audit_records"
        );

        for (String tableDef : requiredTables) {
            assertTrue(v1Content.contains(tableDef), "V1 must contain: " + tableDef);
        }

        // Verify currency and monetary precision
        assertTrue(v1Content.contains("DECIMAL(19, 2)"), "Monetary columns must be DECIMAL(19, 2)");
        assertFalse(v1Content.contains("DECIMAL(19, 4)"), "DECIMAL(19, 4) must not be present");
        assertTrue(v1Content.contains("currency CHAR(3) NOT NULL DEFAULT 'INR'"), "Currency must default to INR");
        assertFalse(v1Content.contains("USD"), "USD currency must not be present");

        // Verify Account Type column
        assertTrue(v1Content.contains("account_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER'"), "accounts table must define account_type");
        assertTrue(v1Content.contains("chk_account_type CHECK (account_type IN ('CUSTOMER', 'SYSTEM_CLEARING'))"), "account_type constraint must exist");

        // Verify Reversal Protection Generated Column & Unique Constraint
        assertTrue(v1Content.contains("reversal_target_id VARCHAR(36) GENERATED ALWAYS AS"), "transactions must have generated column reversal_target_id");
        assertTrue(v1Content.contains("uq_single_successful_reversal UNIQUE (reversal_target_id)"), "Reversal uniqueness constraint must exist");

        // Verify Statement Events sequence number and running balance
        assertTrue(v1Content.contains("sequence_number BIGINT NOT NULL"), "statement_events must include sequence_number");
        assertTrue(v1Content.contains("running_balance DECIMAL(19, 2) NOT NULL"), "statement_events must include running_balance");
        assertTrue(v1Content.contains("initiating_user_id BIGINT NOT NULL"), "statement_events must include initiating_user_id");
        assertTrue(v1Content.contains("uq_account_sequence UNIQUE (account_id, sequence_number)"), "statement_events must enforce unique per-account sequence");

        // Verify Idempotency composite uniqueness
        assertTrue(v1Content.contains("uq_user_op_key UNIQUE (user_id, operation_type, idempotency_key)"), "idempotency_keys must enforce composite uniqueness");

        // Verify Transaction status supports PENDING, POSTED, REFUSED
        assertTrue(v1Content.contains("status IN ('PENDING', 'POSTED', 'REFUSED')"), "transactions status constraint must support PENDING, POSTED, and REFUSED");

        // Verify Ledger Entries duplicate and sequence uniqueness constraints
        assertTrue(v1Content.contains("uq_ledger_tx_entry UNIQUE (transaction_id, entry_type)"), "ledger_entries must enforce uniqueness per transaction entry type");
        assertTrue(v1Content.contains("uq_ledger_account_sequence UNIQUE (account_id, sequence_number)"), "ledger_entries must enforce uniqueness per account sequence");
    }

    @Test
    @DisplayName("V2 Seed: Verify SYSTEM_CLEARING account initialization")
    void testV2SeedSystemClearingAccount() throws IOException {
        String v2Content = Files.readString(MIGRATIONS_DIR.resolve("V2__seed_system_accounts.sql"));
        assertTrue(v2Content.contains("ACC-SYSTEM-CLEARING-001"), "V2 must seed ACC-SYSTEM-CLEARING-001");
        assertTrue(v2Content.contains("SYSTEM_CLEARING"), "V2 must set account_type to SYSTEM_CLEARING");
        assertTrue(v2Content.contains("0.00"), "Initial balance must be 0.00");
    }

    @Test
    @DisplayName("V3 Triggers: Verify append-only immutability triggers for ledger, statements, and transaction lifecycle")
    void testV3ImmutabilityTriggers() throws IOException {
        String v3Content = Files.readString(MIGRATIONS_DIR.resolve("V3__ledger_immutability_triggers.sql"));

        assertTrue(v3Content.contains("CREATE TRIGGER trg_ledger_prevent_update"), "Must contain trigger preventing UPDATE on ledger_entries");
        assertTrue(v3Content.contains("CREATE TRIGGER trg_ledger_prevent_delete"), "Must contain trigger preventing DELETE on ledger_entries");
        assertTrue(v3Content.contains("CREATE TRIGGER trg_stmt_prevent_update"), "Must contain trigger preventing UPDATE on statement_events");
        assertTrue(v3Content.contains("CREATE TRIGGER trg_stmt_prevent_delete"), "Must contain trigger preventing DELETE on statement_events");
        assertTrue(v3Content.contains("CREATE TRIGGER trg_tx_prevent_insert_posted"), "Must contain trigger preventing direct INSERT of POSTED transactions");
        assertTrue(v3Content.contains("CREATE TRIGGER trg_tx_prevent_finalized_update"), "Must contain trigger preventing UPDATE on finalized transactions");
        assertTrue(v3Content.contains("CREATE TRIGGER trg_tx_prevent_delete"), "Must contain trigger preventing DELETE on transactions");

        assertTrue(v3Content.contains("SIGNAL SQLSTATE '45000'"), "Triggers must raise SQLSTATE 45000");
    }

    @Test
    @DisplayName("V4 Grants, Procedures & Views: Verify write boundaries, continuous audit view, and least privilege")
    void testV4GrantsAndImbalanceView() throws IOException {
        String v4Content = Files.readString(MIGRATIONS_DIR.resolve("V4__app_user_grants_and_views.sql"));

        // Verify imbalance detection view
        assertTrue(v4Content.contains("CREATE OR REPLACE VIEW v_unbalanced_transactions"), "V4 must create view v_unbalanced_transactions");
        assertTrue(v4Content.contains("HAVING net_imbalance != 0.00"), "View must filter for non-zero net imbalance");

        // Verify routine definer user grants (narrow DML scope before procedure creation)
        assertTrue(v4Content.contains("GRANT SELECT, UPDATE ON accounts TO '${definerUser}'@'%'"), "Must grant SELECT, UPDATE on accounts to definerUser");
        assertTrue(v4Content.contains("GRANT SELECT, UPDATE ON transactions TO '${definerUser}'@'%'"), "Must grant SELECT, UPDATE on transactions to definerUser");
        assertTrue(v4Content.contains("GRANT SELECT, INSERT ON ledger_entries TO '${definerUser}'@'%'"), "Must grant SELECT, INSERT on ledger_entries to definerUser");
        assertTrue(v4Content.contains("GRANT SELECT, INSERT ON statement_events TO '${definerUser}'@'%'"), "Must grant SELECT, INSERT on statement_events to definerUser");

        // Verify stored procedure write boundaries with dedicated definer and SQL SECURITY DEFINER
        assertTrue(v4Content.contains("CREATE DEFINER = '${definerUser}'@'%' PROCEDURE sp_post_ledger_pair"), "Must define sp_post_ledger_pair with dedicated definer");
        assertTrue(v4Content.contains("CREATE DEFINER = '${definerUser}'@'%' PROCEDURE sp_record_statement_event"), "Must define sp_record_statement_event with dedicated definer");
        assertTrue(v4Content.contains("SQL SECURITY DEFINER"), "Procedures must use SQL SECURITY DEFINER");
        assertTrue(v4Content.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"), "Procedures must define SQLEXCEPTION handler");
        assertTrue(v4Content.contains("RESIGNAL;"), "Procedures must use RESIGNAL to propagate exceptions to Spring transaction manager");
        assertFalse(v4Content.contains("ROLLBACK;"), "Procedures must NOT execute independent ROLLBACK; Spring must own the transaction lifecycle");
        assertFalse(v4Content.contains("COMMIT;"), "Procedures must NOT execute independent COMMIT; Spring must own the transaction lifecycle");
        assertFalse(v4Content.contains("START TRANSACTION"), "Procedures must NOT execute independent START TRANSACTION");

        // Verify procedure validation checks
        assertTrue(v4Content.contains("VIOLATION: Transaction is already POSTED (duplicate posting rejected)."), "Must reject duplicate posting");
        assertTrue(v4Content.contains("VIOLATION: Transaction must be in PENDING status to post."), "Must validate PENDING status");
        assertTrue(v4Content.contains("VIOLATION: Ledger entries already exist for this transaction."), "Must validate no existing entries");
        assertTrue(v4Content.contains("VIOLATION: Caller initiating user ID does not match persisted transaction."), "Must reject forged initiator");
        assertTrue(v4Content.contains("VIOLATION: Caller source balance_after does not match persisted derivation."), "Must reject forged balance");
        assertTrue(v4Content.contains("VIOLATION: Caller source sequence_number does not match monotonic sequence."), "Must reject forged sequence");

        // Verify EXECUTE permission is granted on procedures
        assertTrue(v4Content.contains("GRANT EXECUTE ON PROCEDURE sp_post_ledger_pair TO '${appUser}'@'%'"), "Must grant EXECUTE on sp_post_ledger_pair");
        assertTrue(v4Content.contains("GRANT EXECUTE ON PROCEDURE sp_record_statement_event TO '${appUser}'@'%'"), "Must grant EXECUTE on sp_record_statement_event");

        // Verify operational table grants (column-restricted on accounts to protect balance)
        assertTrue(v4Content.contains("GRANT INSERT ON accounts TO '${appUser}'@'%'"), "Must grant INSERT on accounts");
        assertTrue(v4Content.contains("GRANT UPDATE (name, status) ON accounts TO '${appUser}'@'%'"), "Must grant UPDATE restricted to non-balance columns on accounts");
        assertTrue(v4Content.contains("GRANT INSERT, UPDATE ON transactions TO '${appUser}'@'%'"), "Must grant INSERT, UPDATE on transactions");
        assertTrue(v4Content.contains("GRANT INSERT, UPDATE ON idempotency_keys TO '${appUser}'@'%'"), "Must grant INSERT, UPDATE on idempotency_keys");
        assertTrue(v4Content.contains("GRANT INSERT, UPDATE ON users TO '${appUser}'@'%'"), "Must grant INSERT, UPDATE on users");
        assertTrue(v4Content.contains("GRANT INSERT, UPDATE ON account_permissions TO '${appUser}'@'%'"), "Must grant INSERT, UPDATE on account_permissions");
        assertTrue(v4Content.contains("GRANT INSERT ON audit_records TO '${appUser}'@'%'"), "Must grant INSERT on audit_records");

        // Verify NO direct INSERT or UPDATE is granted on ledger_entries or statement_events
        assertFalse(v4Content.contains("GRANT INSERT ON ledger_entries"), "Must NOT grant direct INSERT on ledger_entries");
        assertFalse(v4Content.contains("GRANT INSERT ON statement_events"), "Must NOT grant direct INSERT on statement_events");
        assertFalse(v4Content.contains("GRANT UPDATE ON ledger_entries"), "Must NOT grant direct UPDATE on ledger_entries");
        assertFalse(v4Content.contains("GRANT UPDATE ON statement_events"), "Must NOT grant direct UPDATE on statement_events");
        assertFalse(v4Content.contains("GRANT UPDATE ON audit_records"), "Must NOT grant direct UPDATE on audit_records");

        // Verify DELETE is NOT granted on any table
        assertFalse(v4Content.contains("GRANT DELETE"), "Must NOT grant DELETE on any table");

        // Verify repeatable procedure creation (idempotent retry defense)
        assertTrue(v4Content.contains("DROP PROCEDURE IF EXISTS sp_post_ledger_pair"), "V4 must drop sp_post_ledger_pair if exists for idempotent retries");
        assertTrue(v4Content.contains("DROP PROCEDURE IF EXISTS sp_record_statement_event"), "V4 must drop sp_record_statement_event if exists for idempotent retries");

        // Verify FLUSH PRIVILEGES is NOT present (requires RELOAD privilege and is redundant)
        assertFalse(v4Content.contains("FLUSH PRIVILEGES"), "V4 must NOT execute FLUSH PRIVILEGES (requires RELOAD privilege)");
    }

    @Test
    @DisplayName("V4 Stored Procedure Declarations: Verify that local variable declarations precede handler declarations (MySQL Error 1337 defense)")
    void testV4StoredProcedureDeclarationOrder() throws IOException {
        String v4Content = Files.readString(MIGRATIONS_DIR.resolve("V4__app_user_grants_and_views.sql"));

        // 1. Check sp_post_ledger_pair
        int spPostStart = v4Content.indexOf("CREATE DEFINER = '${definerUser}'@'%' PROCEDURE sp_post_ledger_pair");
        assertTrue(spPostStart > 0, "sp_post_ledger_pair must exist in V4");
        int spPostEnd = v4Content.indexOf("END //", spPostStart);
        assertTrue(spPostEnd > spPostStart, "sp_post_ledger_pair must have closing END //");
        String spPostBody = v4Content.substring(spPostStart, spPostEnd);

        int postHandlerIndex = spPostBody.indexOf("DECLARE EXIT HANDLER FOR SQLEXCEPTION");
        assertTrue(postHandlerIndex > 0, "sp_post_ledger_pair must define EXIT HANDLER");

        // Verify that every variable declaration appears before the handler
        int lastPostVarIndex = spPostBody.lastIndexOf("DECLARE v_");
        assertTrue(lastPostVarIndex > 0, "sp_post_ledger_pair must declare local variables");
        assertTrue(lastPostVarIndex < postHandlerIndex,
                "In sp_post_ledger_pair, all variable declarations must precede handler declarations (MySQL Error 1337)");

        // Verify no DECLARE statement appears after the handler
        int postNextDeclare = spPostBody.indexOf("DECLARE ", postHandlerIndex + 1);
        assertEquals(-1, postNextDeclare,
                "In sp_post_ledger_pair, no DECLARE statement is permitted after the handler declaration");

        // 2. Check sp_record_statement_event
        int spRecordStart = v4Content.indexOf("CREATE DEFINER = '${definerUser}'@'%' PROCEDURE sp_record_statement_event");
        assertTrue(spRecordStart > 0, "sp_record_statement_event must exist in V4");
        int spRecordEnd = v4Content.indexOf("END //", spRecordStart);
        assertTrue(spRecordEnd > spRecordStart, "sp_record_statement_event must have closing END //");
        String spRecordBody = v4Content.substring(spRecordStart, spRecordEnd);

        int recordHandlerIndex = spRecordBody.indexOf("DECLARE EXIT HANDLER FOR SQLEXCEPTION");
        assertTrue(recordHandlerIndex > 0, "sp_record_statement_event must define EXIT HANDLER");

        int lastRecordVarIndex = spRecordBody.lastIndexOf("DECLARE v_");
        assertTrue(lastRecordVarIndex > 0, "sp_record_statement_event must declare local variables");
        assertTrue(lastRecordVarIndex < recordHandlerIndex,
                "In sp_record_statement_event, all variable declarations must precede handler declarations (MySQL Error 1337)");

        int recordNextDeclare = spRecordBody.indexOf("DECLARE ", recordHandlerIndex + 1);
        assertEquals(-1, recordNextDeclare,
                "In sp_record_statement_event, no DECLARE statement is permitted after the handler declaration");
    }

    @Test
    @DisplayName("V5 Grants: Verify definer user routine EXECUTE grants")
    void testV5DefinerRoutineExecuteGrants() throws IOException {
        Path v5Path = MIGRATIONS_DIR.resolve("V5__grant_definer_routine_execute.sql");
        assertTrue(Files.exists(v5Path), "V5__grant_definer_routine_execute.sql must exist");
        String v5Content = Files.readString(v5Path);

        // Verify exact routine grants to definerUser placeholder
        assertTrue(v5Content.contains("GRANT EXECUTE ON PROCEDURE sp_post_ledger_pair TO '${definerUser}'@'%';"),
                "V5 must grant EXECUTE on sp_post_ledger_pair to definerUser");
        assertTrue(v5Content.contains("GRANT EXECUTE ON PROCEDURE sp_record_statement_event TO '${definerUser}'@'%';"),
                "V5 must grant EXECUTE on sp_record_statement_event to definerUser");

        // Verify no extra privileges are granted
        assertFalse(v5Content.contains("GRANT ALL"), "V5 must NOT grant ALL PRIVILEGES");
        assertFalse(v5Content.contains("GRANT SELECT"), "V5 must NOT grant SELECT");
        assertFalse(v5Content.contains("GRANT INSERT"), "V5 must NOT grant INSERT");
        assertFalse(v5Content.contains("GRANT UPDATE"), "V5 must NOT grant UPDATE");
        assertFalse(v5Content.contains("GRANT DELETE"), "V5 must NOT grant DELETE");
        assertFalse(v5Content.contains("FLUSH PRIVILEGES"), "V5 must NOT execute FLUSH PRIVILEGES");
        assertFalse(v5Content.lines().filter(l -> !l.trim().startsWith("--")).anyMatch(l -> l.contains("${appUser}")),
                "V5 SQL statements must NOT grant or reference appUser");
    }
}
