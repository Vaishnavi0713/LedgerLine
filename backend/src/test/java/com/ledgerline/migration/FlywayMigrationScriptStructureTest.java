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
    }
}
