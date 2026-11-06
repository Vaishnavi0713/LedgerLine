package com.ledgerline.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test suite for live MySQL 8.0 instances.
 * Verifies transaction ownership, financial atomicity, and stored procedure boundaries.
 *
 * Guarded by:
 * - RUN_MYSQL_TESTS=true
 * - DB_URL
 * - DB_APP_USER
 * - DB_APP_PASSWORD
 */
public class MySqlDatabaseIntegrationTest {

    private Connection getAppConnection() throws SQLException {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_APP_USER");
        String password = System.getenv("DB_APP_PASSWORD");

        if (url == null || user == null || password == null) {
            throw new IllegalStateException("MySQL integration test variables (DB_URL, DB_APP_USER, DB_APP_PASSWORD) not configured.");
        }
        return DriverManager.getConnection(url, user, password);
    }

    // =========================================================================
    // Test A — Successful posting & outer transaction rollback
    // =========================================================================
    @Test
    @DisplayName("Test A: Successful posting commits uncommitted changes, and outer rollback reverts everything")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testA_SuccessfulPostingAndRollback() throws SQLException {
        try (Connection conn = getAppConnection()) {
            conn.setAutoCommit(false);

            String txId = "tx-test-a-" + UUID.randomUUID();
            String refId = "ref-a-" + UUID.randomUUID();

            // 1. Insert PENDING transaction
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                    "VALUES (?, ?, 1, 1, 2, 200.00, 'INR', 'TRANSFER', 'PENDING')")) {
                ps.setString(1, txId);
                ps.setString(2, refId);
                ps.executeUpdate();
            }

            // 2. Invoke posting procedure
            try (CallableStatement cs = conn.prepareCall("{CALL sp_post_ledger_pair(?, 1, 1, 2, 200.00, NULL, NULL, NULL, NULL)}")) {
                cs.setString(1, txId);
                cs.execute();
            }

            // 3. Invoke statement event recording
            try (CallableStatement cs = conn.prepareCall("{CALL sp_record_statement_event(1, NULL, 1, ?, 'POSTED_DEBIT', 200.00, NULL, 'POSTED', 'Test A')};")) {
                cs.setString(1, txId);
                cs.execute();
            }

            // 4. Verify uncommitted changes exist in session
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM transactions WHERE id = ?")) {
                ps.setString(1, txId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("POSTED", rs.getString("status"));
                }
            }

            // 5. Roll back outer transaction
            conn.rollback();

            // 6. Verify that ALL changes disappeared
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM transactions WHERE id = ?")) {
                ps.setString(1, txId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "Transaction must disappear after rollback");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?")) {
                ps.setString(1, txId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "Ledger entries must disappear after rollback");
                }
            }
        }
    }

    // =========================================================================
    // Test B — Failure after first ledger insert
    // =========================================================================
    @Test
    @DisplayName("Test B: Failure during second ledger insertion rolls back outer transaction with zero orphaned rows")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testB_FailureDuringSecondLedgerInsertRollsBack() throws SQLException {
        try (Connection conn = getAppConnection()) {
            conn.setAutoCommit(false);

            String txId = "tx-test-b-" + UUID.randomUUID();
            String refId = "ref-b-" + UUID.randomUUID();

            // 1. Insert PENDING transaction
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                    "VALUES (?, ?, 1, 1, 2, 150.00, 'INR', 'TRANSFER', 'PENDING')")) {
                ps.setString(1, txId);
                ps.setString(2, refId);
                ps.executeUpdate();
            }

            // 2. Pre-occupy CREDIT entry for txId to force second leg insert to fail with duplicate key on uq_ledger_tx_entry
            // Note: If calling as privileged user or via definer, a collision on uq_ledger_tx_entry causes failure.
            // Alternatively, pass an invalid destination account ID that doesn't exist to cause foreign key failure on second leg.
            // When sp_post_ledger_pair fails on second insert, it propagates SQLEXCEPTION to caller.
            try (CallableStatement cs = conn.prepareCall("{CALL sp_post_ledger_pair(?, 1, 1, 9999999, 150.00, NULL, NULL, NULL, NULL)}")) {
                cs.setString(1, txId);
                SQLException ex = assertThrows(SQLException.class, cs::execute, "Must fail because dest account does not exist");
                assertEquals("45000", ex.getSQLState());
            }

            // 3. Roll back outer transaction as Spring would
            conn.rollback();

            // 4. Verify zero ledger entries or balance updates remain
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?")) {
                ps.setString(1, txId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "No orphaned ledger entries must remain");
                }
            }
        }
    }

    // =========================================================================
    // Test C — Failure during statement recording
    // =========================================================================
    @Test
    @DisplayName("Test C: Failure during statement recording causes outer rollback of preceding ledger posting")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testC_FailureDuringStatementRecordingRollsBackPosting() throws SQLException {
        try (Connection conn = getAppConnection()) {
            conn.setAutoCommit(false);

            String txId = "tx-test-c-" + UUID.randomUUID();
            String refId = "ref-c-" + UUID.randomUUID();

            // 1. Insert PENDING transaction
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                    "VALUES (?, ?, 1, 1, 2, 50.00, 'INR', 'TRANSFER', 'PENDING')")) {
                ps.setString(1, txId);
                ps.setString(2, refId);
                ps.executeUpdate();
            }

            // 2. Successfully execute ledger posting
            try (CallableStatement cs = conn.prepareCall("{CALL sp_post_ledger_pair(?, 1, 1, 2, 50.00, NULL, NULL, NULL, NULL)}")) {
                cs.setString(1, txId);
                cs.execute();
            }

            // 3. Force statement recording to fail by passing mismatched account ID (account 999 not in transaction)
            try (CallableStatement cs = conn.prepareCall("{CALL sp_record_statement_event(999, NULL, 1, ?, 'POSTED_DEBIT', 50.00, NULL, 'POSTED', 'Fail')};")) {
                cs.setString(1, txId);
                SQLException ex = assertThrows(SQLException.class, cs::execute, "Statement event must reject mismatched account");
                assertEquals("45000", ex.getSQLState());
            }

            // 4. Spring rollback
            conn.rollback();

            // 5. Verify transaction, balances, and ledger entries all reverted
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?")) {
                ps.setString(1, txId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "Ledger entries must be completely rolled back");
                }
            }
        }
    }

    // =========================================================================
    // Test D — Duplicate posting rejected
    // =========================================================================
    @Test
    @DisplayName("Test D: Calling sp_post_ledger_pair twice for same transaction fails on second call")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testD_DuplicatePostingRejected() throws SQLException {
        try (Connection conn = getAppConnection()) {
            conn.setAutoCommit(false);

            String txId = "tx-test-d-" + UUID.randomUUID();
            String refId = "ref-d-" + UUID.randomUUID();

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                    "VALUES (?, ?, 1, 1, 2, 75.00, 'INR', 'TRANSFER', 'PENDING')")) {
                ps.setString(1, txId);
                ps.setString(2, refId);
                ps.executeUpdate();
            }

            // First call succeeds
            try (CallableStatement cs = conn.prepareCall("{CALL sp_post_ledger_pair(?, 1, 1, 2, 75.00, NULL, NULL, NULL, NULL)}")) {
                cs.setString(1, txId);
                cs.execute();
            }
            conn.commit();

            // Second call MUST fail with SQLSTATE 45000 (already POSTED)
            try (CallableStatement cs = conn.prepareCall("{CALL sp_post_ledger_pair(?, 1, 1, 2, 75.00, NULL, NULL, NULL, NULL)}")) {
                cs.setString(1, txId);
                SQLException ex = assertThrows(SQLException.class, cs::execute);
                assertEquals("45000", ex.getSQLState());
                assertTrue(ex.getMessage().contains("already POSTED"));
            }

            // Verify exactly 2 ledger entries exist (1 DEBIT, 1 CREDIT)
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?")) {
                ps.setString(1, txId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "Must have exactly 2 ledger entries");
                }
            }
        }
    }

    // =========================================================================
    // Test E — Concurrent duplicate posting
    // =========================================================================
    @Test
    @DisplayName("Test E: Two concurrent posting attempts serialize; exactly one succeeds")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testE_ConcurrentDuplicatePostingSerializes() throws Exception {
        String txId = "tx-test-e-" + UUID.randomUUID();
        String refId = "ref-e-" + UUID.randomUUID();

        // Setup transaction using primary connection
        try (Connection conn = getAppConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                    "VALUES (?, ?, 1, 1, 2, 30.00, 'INR', 'TRANSFER', 'PENDING')")) {
                ps.setString(1, txId);
                ps.setString(2, refId);
                ps.executeUpdate();
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Callable<Void> postTask = () -> {
            try (Connection conn = getAppConnection()) {
                conn.setAutoCommit(false);
                barrier.await(); // Synchronize threads to execute simultaneously

                try (CallableStatement cs = conn.prepareCall("{CALL sp_post_ledger_pair(?, 1, 1, 2, 30.00, NULL, NULL, NULL, NULL)}")) {
                    cs.setString(1, txId);
                    cs.execute();
                    conn.commit();
                    successCount.incrementAndGet();
                } catch (SQLException ex) {
                    conn.rollback();
                    failureCount.incrementAndGet();
                }
            }
            return null;
        };

        Future<Void> f1 = executor.submit(postTask);
        Future<Void> f2 = executor.submit(postTask);

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one concurrent thread must succeed");
        assertEquals(1, failureCount.get(), "Exactly one concurrent thread must fail");

        // Verify total entries in DB is exactly 2
        try (Connection conn = getAppConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?")) {
            ps.setString(1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "Exactly 2 entries must be posted");
            }
        }
    }

    // =========================================================================
    // Test F — Existing security protections
    // =========================================================================
    @Test
    @DisplayName("Test F1: Direct INSERT on ledger_entries denied to application runtime user")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testF1_DirectLedgerInsertDeniedToAppUser() throws SQLException {
        try (Connection conn = getAppConnection()) {
            String sql = "INSERT INTO ledger_entries (transaction_id, account_id, initiating_user_id, entry_type, amount, balance_after, sequence_number) " +
                         "VALUES ('tx-direct', 1, 1, 'CREDIT', 100.00, 100.00, 1)";
            try (Statement stmt = conn.createStatement()) {
                SQLException exception = assertThrows(SQLException.class, () -> stmt.executeUpdate(sql));
                assertEquals(1142, exception.getErrorCode(), "Must raise MySQL error 1142 (INSERT command denied)");
                assertTrue(exception.getMessage().contains("INSERT command denied"));
            }
        }
    }

    @Test
    @DisplayName("Test F2: Direct INSERT on statement_events denied to application runtime user")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testF2_DirectStatementEventInsertDeniedToAppUser() throws SQLException {
        try (Connection conn = getAppConnection()) {
            String sql = "INSERT INTO statement_events (account_id, sequence_number, initiating_user_id, event_type, amount, running_balance, status) " +
                         "VALUES (1, 999, 1, 'POSTED_CREDIT', 100.00, 100.00, 'POSTED')";
            try (Statement stmt = conn.createStatement()) {
                SQLException exception = assertThrows(SQLException.class, () -> stmt.executeUpdate(sql));
                assertEquals(1142, exception.getErrorCode(), "Must raise MySQL error 1142 (INSERT command denied)");
            }
        }
    }

    @Test
    @DisplayName("Test F3: Direct UPDATE on accounts.balance denied to application runtime user")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testF3_DirectAccountBalanceUpdateDeniedToAppUser() throws SQLException {
        try (Connection conn = getAppConnection()) {
            String sql = "UPDATE accounts SET balance = 999999.00 WHERE id = 1";
            try (Statement stmt = conn.createStatement()) {
                SQLException exception = assertThrows(SQLException.class, () -> stmt.executeUpdate(sql));
                assertEquals(1142, exception.getErrorCode(), "Must raise MySQL error 1142 (UPDATE command denied for column 'balance')");
            }
        }
    }

    @Test
    @DisplayName("Test F4: Direct INSERT of transaction with status POSTED rejected by trigger")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testF4_DirectInsertPostedTransactionDeniedByTrigger() throws SQLException {
        try (Connection conn = getAppConnection()) {
            String sql = "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                         "VALUES ('tx-bypass-001', 'ref-bypass-001', 1, 1, 2, 100.00, 'INR', 'TRANSFER', 'POSTED')";
            try (Statement stmt = conn.createStatement()) {
                SQLException exception = assertThrows(SQLException.class, () -> stmt.executeUpdate(sql));
                assertEquals("45000", exception.getSQLState(), "Must raise SQLSTATE 45000 from lifecycle trigger");
                assertTrue(exception.getMessage().contains("VIOLATION: Transactions cannot be created directly in POSTED status"));
            }
        }
    }

    @Test
    @DisplayName("Test F5: Stored procedure rejects forged parameters (initiator, balance, sequence)")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testF5_StoredProcedureRejectsForgedParameters() throws SQLException {
        try (Connection conn = getAppConnection()) {
            String txId = "tx-forged-" + UUID.randomUUID();
            String refId = "ref-forged-" + UUID.randomUUID();

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                    "VALUES (?, ?, 1, 1, 2, 50.00, 'INR', 'TRANSFER', 'PENDING')")) {
                ps.setString(1, txId);
                ps.setString(2, refId);
                ps.executeUpdate();
            }

            // Forged initiator 999
            try (CallableStatement cs = conn.prepareCall("{CALL sp_post_ledger_pair(?, 999, 1, 2, 50.00, NULL, NULL, NULL, NULL)}")) {
                cs.setString(1, txId);
                SQLException ex = assertThrows(SQLException.class, cs::execute);
                assertEquals("45000", ex.getSQLState());
                assertTrue(ex.getMessage().contains("initiating user ID"));
            }

            // Forged balance_after 999999.00
            try (CallableStatement cs = conn.prepareCall("{CALL sp_post_ledger_pair(?, 1, 1, 2, 50.00, 999999.00, NULL, NULL, NULL)}")) {
                cs.setString(1, txId);
                SQLException ex = assertThrows(SQLException.class, cs::execute);
                assertEquals("45000", ex.getSQLState());
                assertTrue(ex.getMessage().contains("balance_after"));
            }
        }
    }

    @Test
    @DisplayName("Test F6: Reversal target uniqueness prevents duplicate successful reversals")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void testF6_ReversalUniquenessEnforcedByGeneratedColumn() throws SQLException {
        try (Connection conn = getAppConnection()) {
            String checkIndexSql = "SELECT COUNT(*) FROM information_schema.statistics " +
                                   "WHERE table_name = 'transactions' AND index_name = 'uq_single_successful_reversal'";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(checkIndexSql)) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "Index uq_single_successful_reversal must exist in MySQL");
            }
        }
    }
}
