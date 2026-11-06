package com.ledgerline.integration;

import com.ledgerline.entity.StatementEventType;
import com.ledgerline.exception.StoredProcedureException;
import com.ledgerline.exception.StoredProcedureViolationException;
import com.ledgerline.gateway.StoredProcedureGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live MySQL 8.0 integration tests for StoredProcedureGateway.
 * Invokes existing procedures sp_post_ledger_pair and sp_record_statement_event
 * against the isolated ledgerline_test_db database.
 *
 * Verifies:
 * - Proper parameter mapping.
 * - Row locking and balance derivation.
 * - Double-entry ledger entry generation.
 * - Monotonic statement sequence increments.
 * - SQLSTATE 45000 violation exception mapping.
 * - Spring transaction participation and rollback atomicity.
 *
 * Guarded by RUN_MYSQL_TESTS=true
 */
public class MySqlStoredProcedureGatewayIntegrationTest {

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager txManager;
    private TransactionTemplate txTemplate;
    private StoredProcedureGateway gateway;

    private DataSource getAppDataSource() {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_APP_USER");
        String password = System.getenv("DB_APP_PASSWORD");

        if (url == null || user == null || password == null) {
            throw new IllegalStateException("MySQL integration test variables (DB_URL, DB_APP_USER, DB_APP_PASSWORD) not configured.");
        }
        if (!url.contains("useAffectedRows=")) {
            url += (url.contains("?") ? "&" : "?") + "useAffectedRows=true";
        }
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        return ds;
    }

    @BeforeEach
    void setUp() {
        if ("true".equalsIgnoreCase(System.getenv("RUN_MYSQL_TESTS"))) {
            dataSource = getAppDataSource();
            jdbcTemplate = new JdbcTemplate(dataSource);
            txManager = new DataSourceTransactionManager(dataSource);
            txTemplate = new TransactionTemplate(txManager);
            gateway = new StoredProcedureGateway(jdbcTemplate);
        }
    }

    private Long createTestCustomerAccount(String name, BigDecimal initialBalance) {
        String accNum = "ACC-TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbcTemplate.update(
                "INSERT INTO accounts (account_number, name, account_type, currency, balance, statement_sequence_counter, status) " +
                "VALUES (?, ?, 'CUSTOMER', 'INR', ?, 0, 'ACTIVE')",
                accNum, name, initialBalance
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE account_number = ?", Long.class, accNum
        );
    }

    @Test
    @DisplayName("Test 1: sp_post_ledger_pair executes atomically, updates balances, and creates balanced ledger pair")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test1_SuccessfulPostLedgerPair() {
        Long srcId = createTestCustomerAccount("Source A", new BigDecimal("1000.00"));
        Long destId = createTestCustomerAccount("Dest B", new BigDecimal("500.00"));
        String txId = UUID.randomUUID().toString();
        String refId = "ref-gate-1-" + UUID.randomUUID();

        txTemplate.execute(status -> {
            // 1. Create PENDING transaction
            jdbcTemplate.update(
                    "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                    "VALUES (?, ?, 1, ?, ?, 400.00, 'INR', 'TRANSFER', 'PENDING')",
                    txId, refId, srcId, destId
            );

            // 2. Invoke gateway to post ledger pair
            gateway.postLedgerPair(txId, 1L, srcId, destId, new BigDecimal("400.00"));
            return null;
        });

        // 3. Verify balances updated
        BigDecimal srcBalance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, srcId);
        BigDecimal destBalance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, destId);
        assertEquals(new BigDecimal("600.00"), srcBalance);
        assertEquals(new BigDecimal("900.00"), destBalance);

        // 4. Verify transaction transitioned to POSTED
        String txStatus = jdbcTemplate.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, txId);
        assertEquals("POSTED", txStatus);

        // 5. Verify 2 balanced ledger entries created
        Integer ledgerCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?", Integer.class, txId);
        assertEquals(2, ledgerCount);

        BigDecimal debitSum = jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM ledger_entries WHERE transaction_id = ? AND entry_type = 'DEBIT'", BigDecimal.class, txId);
        BigDecimal creditSum = jdbcTemplate.queryForObject(
                "SELECT SUM(amount) FROM ledger_entries WHERE transaction_id = ? AND entry_type = 'CREDIT'", BigDecimal.class, txId);
        assertEquals(new BigDecimal("400.00"), debitSum);
        assertEquals(new BigDecimal("400.00"), creditSum);
    }

    @Test
    @DisplayName("Test 2: sp_record_statement_event records posted debit and credit statement legs")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test2_RecordPostedStatementEvents() {
        Long srcId = createTestCustomerAccount("Source 2", new BigDecimal("800.00"));
        Long destId = createTestCustomerAccount("Dest 2", new BigDecimal("200.00"));
        String txId = UUID.randomUUID().toString();
        String refId = "ref-gate-2-" + UUID.randomUUID();

        txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                    "VALUES (?, ?, 1, ?, ?, 200.00, 'INR', 'TRANSFER', 'PENDING')",
                    txId, refId, srcId, destId
            );

            gateway.postLedgerPair(txId, 1L, srcId, destId, new BigDecimal("200.00"));

            // Record statement events for both accounts
            gateway.recordPostedStatementEvent(srcId, txId, 1L, StatementEventType.POSTED_DEBIT, new BigDecimal("200.00"));
            gateway.recordPostedStatementEvent(destId, txId, 1L, StatementEventType.POSTED_CREDIT, new BigDecimal("200.00"));
            return null;
        });

        // Verify statement events
        Integer srcEvents = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM statement_events WHERE account_id = ? AND transaction_id = ?", Integer.class, srcId, txId);
        Integer destEvents = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM statement_events WHERE account_id = ? AND transaction_id = ?", Integer.class, destId, txId);
        assertEquals(1, srcEvents);
        assertEquals(1, destEvents);

        String srcEventType = jdbcTemplate.queryForObject("SELECT event_type FROM statement_events WHERE account_id = ? AND transaction_id = ?", String.class, srcId, txId);
        String destEventType = jdbcTemplate.queryForObject("SELECT event_type FROM statement_events WHERE account_id = ? AND transaction_id = ?", String.class, destId, txId);
        assertEquals("POSTED_DEBIT", srcEventType);
        assertEquals("POSTED_CREDIT", destEventType);
    }

    @Test
    @DisplayName("Test 3: sp_record_statement_event records refused outcome with sequence increment without altering balance")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test3_RecordRefusedStatementEvent() {
        Long customerId = createTestCustomerAccount("Customer 3", new BigDecimal("100.00"));
        String txId = UUID.randomUUID().toString();
        String refId = "ref-refused-" + UUID.randomUUID();

        txTemplate.execute(status -> {
            // Save transaction directly as REFUSED
            jdbcTemplate.update(
                    "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status, refusal_reason) " +
                    "VALUES (?, ?, 1, ?, 1, 5000.00, 'INR', 'WITHDRAWAL', 'REFUSED', 'INSUFFICIENT_FUNDS')",
                    txId, refId, customerId
            );

            // Record refused statement event via gateway
            gateway.recordRefusedStatementEvent(
                    customerId,
                    txId,
                    1L,
                    StatementEventType.REFUSED_WITHDRAWAL,
                    new BigDecimal("5000.00"),
                    "INSUFFICIENT_FUNDS"
            );
            return null;
        });

        // Verify balance was untouched
        BigDecimal balance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, customerId);
        assertEquals(new BigDecimal("100.00"), balance, "Refused transaction must NOT alter account balance");

        // Verify sequence counter was incremented to 1
        Long seqCounter = jdbcTemplate.queryForObject("SELECT statement_sequence_counter FROM accounts WHERE id = ?", Long.class, customerId);
        assertEquals(1L, seqCounter, "Refused transaction must monotonically increment statement sequence counter");

        // Verify statement event
        String eventStatus = jdbcTemplate.queryForObject("SELECT status FROM statement_events WHERE transaction_id = ?", String.class, txId);
        String reason = jdbcTemplate.queryForObject("SELECT reason FROM statement_events WHERE transaction_id = ?", String.class, txId);
        assertEquals("REFUSED", eventStatus);
        assertEquals("INSUFFICIENT_FUNDS", reason);
    }

    @Test
    @DisplayName("Test 4: Insufficient balance throws StoredProcedureViolationException (SQLSTATE 45000)")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test4_InsufficientBalanceThrowsViolationException() {
        Long srcId = createTestCustomerAccount("Poor Customer", new BigDecimal("50.00"));
        Long destId = createTestCustomerAccount("Rich Customer", new BigDecimal("500.00"));
        String txId = UUID.randomUUID().toString();
        String refId = "ref-overdraft-" + UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                "VALUES (?, ?, 1, ?, ?, 200.00, 'INR', 'TRANSFER', 'PENDING')",
                txId, refId, srcId, destId
        );

        StoredProcedureViolationException ex = assertThrows(StoredProcedureViolationException.class, () ->
                gateway.postLedgerPair(txId, 1L, srcId, destId, new BigDecimal("200.00")));

        assertEquals("45000", ex.getSqlState());
        assertTrue(ex.isBusinessViolation());
        assertTrue(ex.getMessage().contains("Insufficient balance in source customer account"));
    }

    @Test
    @DisplayName("Test 5: Caller parameter mismatch (forgery attempt) throws StoredProcedureViolationException")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test5_ParameterMismatchThrowsViolationException() {
        Long srcId = createTestCustomerAccount("Customer 5A", new BigDecimal("500.00"));
        Long destId = createTestCustomerAccount("Customer 5B", new BigDecimal("500.00"));
        String txId = UUID.randomUUID().toString();
        String refId = "ref-tamper-" + UUID.randomUUID();

        // Transaction persisted for 100.00
        jdbcTemplate.update(
                "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                "VALUES (?, ?, 1, ?, ?, 100.00, 'INR', 'TRANSFER', 'PENDING')",
                txId, refId, srcId, destId
        );

        // Caller attempts to execute with amount = 200.00
        StoredProcedureViolationException ex = assertThrows(StoredProcedureViolationException.class, () ->
                gateway.postLedgerPair(txId, 1L, srcId, destId, new BigDecimal("200.00")));

        assertEquals("45000", ex.getSqlState());
        assertTrue(ex.getMessage().contains("Caller amount does not match persisted transaction"));
    }

    @Test
    @DisplayName("Test 6: Inactive account throws StoredProcedureViolationException")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test6_InactiveAccountThrowsViolationException() {
        Long srcId = createTestCustomerAccount("Frozen Source", new BigDecimal("500.00"));
        Long destId = createTestCustomerAccount("Active Dest", new BigDecimal("500.00"));
        jdbcTemplate.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", srcId);

        String txId = UUID.randomUUID().toString();
        String refId = "ref-frozen-" + UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                "VALUES (?, ?, 1, ?, ?, 50.00, 'INR', 'TRANSFER', 'PENDING')",
                txId, refId, srcId, destId
        );

        StoredProcedureViolationException ex = assertThrows(StoredProcedureViolationException.class, () ->
                gateway.postLedgerPair(txId, 1L, srcId, destId, new BigDecimal("50.00")));

        assertEquals("45000", ex.getSqlState());
        assertTrue(ex.getMessage().contains("Both accounts must be ACTIVE"));
    }

    @Test
    @DisplayName("Test 7: Spring transaction rollback clears all stored procedure modifications atomically")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test7_SpringTransactionRollbackClearsAllChanges() {
        Long srcId = createTestCustomerAccount("Rollback Src", new BigDecimal("1000.00"));
        Long destId = createTestCustomerAccount("Rollback Dest", new BigDecimal("500.00"));
        String txId = UUID.randomUUID().toString();
        String refId = "ref-rollback-" + UUID.randomUUID();

        assertThrows(RuntimeException.class, () -> {
            txTemplate.execute(status -> {
                jdbcTemplate.update(
                        "INSERT INTO transactions (id, reference_id, initiating_user_id, source_account_id, destination_account_id, amount, currency, type, status) " +
                        "VALUES (?, ?, 1, ?, ?, 300.00, 'INR', 'TRANSFER', 'PENDING')",
                        txId, refId, srcId, destId
                );

                gateway.postLedgerPair(txId, 1L, srcId, destId, new BigDecimal("300.00"));
                gateway.recordPostedStatementEvent(srcId, txId, 1L, StatementEventType.POSTED_DEBIT, new BigDecimal("300.00"));

                // Simulate unexpected failure later in the service execution -> triggers rollback
                throw new RuntimeException("Simulated unexpected post-processing failure");
            });
        });

        // Verify: outer transaction rollback completely reverted the balances, ledger entries, and statement events!
        BigDecimal srcBalance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, srcId);
        BigDecimal destBalance = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, destId);
        assertEquals(new BigDecimal("1000.00"), srcBalance, "Source balance must be restored after rollback");
        assertEquals(new BigDecimal("500.00"), destBalance, "Destination balance must be restored after rollback");

        Integer ledgerCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?", Integer.class, txId);
        assertEquals(0, ledgerCount, "Ledger entries must be rolled back completely");

        Integer stmtCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM statement_events WHERE transaction_id = ?", Integer.class, txId);
        assertEquals(0, stmtCount, "Statement events must be rolled back completely");

        Integer txCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE id = ?", Integer.class, txId);
        assertEquals(0, txCount, "Transaction record must be rolled back completely");
    }
}
