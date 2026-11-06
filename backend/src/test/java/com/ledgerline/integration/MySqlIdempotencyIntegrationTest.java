package com.ledgerline.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live MySQL 8.0 integration tests for the Idempotency Foundation.
 * Verifies real InnoDB row-level locking, unique constraint uq_user_op_key enforcement,
 * transaction rollback atomicity, and multi-threaded race conditions.
 *
 * Guarded by RUN_MYSQL_TESTS=true
 */
public class MySqlIdempotencyIntegrationTest {

    private Connection getAppConnection() throws SQLException {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_APP_USER");
        String password = System.getenv("DB_APP_PASSWORD");

        if (url == null || user == null || password == null) {
            throw new IllegalStateException("MySQL integration test variables (DB_URL, DB_APP_USER, DB_APP_PASSWORD) not configured.");
        }
        if (!url.contains("useAffectedRows=")) {
            url += (url.contains("?") ? "&" : "?") + "useAffectedRows=true";
        }
        return DriverManager.getConnection(url, user, password);
    }

    @Test
    @DisplayName("Test 1: First request atomically reserves key and updates to COMPLETED")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test1_FirstRequestReservesAndCompletes() throws SQLException {
        String key = "test-key-1-" + UUID.randomUUID();
        String hash = "hash-1-" + UUID.randomUUID();

        try (Connection conn = getAppConnection()) {
            conn.setAutoCommit(false);

            // 1. Reserve via ON DUPLICATE KEY UPDATE id = id
            int inserted;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (1, 'TRANSFER', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, key);
                ps.setString(2, hash);
                inserted = ps.executeUpdate();
            }
            assertEquals(1, inserted, "First reservation must return 1 row affected");

            // 2. Complete operation and cache response
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE idempotency_keys SET status = 'COMPLETED', response_status = 200, " +
                    "response_body = '{\"status\":\"POSTED\",\"txId\":\"tx-101\"}', completed_at = NOW() " +
                    "WHERE user_id = 1 AND operation_type = 'TRANSFER' AND idempotency_key = ?")) {
                ps.setString(1, key);
                int updated = ps.executeUpdate();
                assertEquals(1, updated);
            }

            conn.commit();

            // 3. Verify row in MySQL
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, response_status, response_body FROM idempotency_keys WHERE user_id = 1 AND operation_type = 'TRANSFER' AND idempotency_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("COMPLETED", rs.getString("status"));
                    assertEquals(200, rs.getInt("response_status"));
                    assertTrue(rs.getString("response_body").contains("tx-101"));
                }
            }
        }
    }

    @Test
    @DisplayName("Test 2: Same key and payload returns original response without executing again")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test2_SameKeyAndPayloadReplaysOriginalResponse() throws SQLException {
        String key = "test-key-2-" + UUID.randomUUID();
        String hash = "hash-2-" + UUID.randomUUID();
        String originalResponse = "{\"status\":\"POSTED\",\"txId\":\"tx-orig-202\"}";

        try (Connection conn = getAppConnection()) {
            // Setup completed record
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, response_status, response_body, expires_at) " +
                    "VALUES (1, 'DEPOSIT', ?, ?, 'COMPLETED', 200, ?, NOW() + INTERVAL 1 DAY)")) {
                ps.setString(1, key);
                ps.setString(2, hash);
                ps.setString(3, originalResponse);
                ps.executeUpdate();
            }

            // Second request: attempts insert with same key and hash via ON DUPLICATE KEY UPDATE id = id
            int duplicateInsert;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (1, 'DEPOSIT', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, key);
                ps.setString(2, hash);
                duplicateInsert = ps.executeUpdate();
            }
            assertEquals(0, duplicateInsert, "Duplicate key must yield 0 rows affected via ON DUPLICATE KEY UPDATE id = id");

            // Query existing record: hash matches and status is COMPLETED -> replay
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT request_hash, status, response_status, response_body FROM idempotency_keys WHERE user_id = 1 AND operation_type = 'DEPOSIT' AND idempotency_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(hash, rs.getString("request_hash"));
                    assertEquals("COMPLETED", rs.getString("status"));
                    assertEquals(200, rs.getInt("response_status"));
                    assertEquals(originalResponse, rs.getString("response_body"));
                }
            }
        }
    }

    @Test
    @DisplayName("Test 3: Same key with different payload detects hash mismatch (HTTP 409 condition)")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test3_SameKeyWithDifferentPayloadDetectsMismatch() throws SQLException {
        String key = "test-key-3-" + UUID.randomUUID();
        String originalHash = "original-hash-" + UUID.randomUUID();
        String tamperedHash = "tampered-hash-" + UUID.randomUUID();

        try (Connection conn = getAppConnection()) {
            // Seed original completed idempotency key
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, response_status, response_body, expires_at) " +
                    "VALUES (1, 'TRANSFER', ?, ?, 'COMPLETED', 200, '{\"status\":\"POSTED\"}', NOW() + INTERVAL 1 DAY)")) {
                ps.setString(1, key);
                ps.setString(2, originalHash);
                ps.executeUpdate();
            }

            // Request with different payload: ON DUPLICATE KEY UPDATE id = id returns 0
            int inserted;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (1, 'TRANSFER', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, key);
                ps.setString(2, tamperedHash);
                inserted = ps.executeUpdate();
            }
            assertEquals(0, inserted);

            // Fetch stored hash and verify mismatch
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT request_hash FROM idempotency_keys WHERE user_id = 1 AND operation_type = 'TRANSFER' AND idempotency_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    String storedHash = rs.getString("request_hash");
                    assertNotEquals(tamperedHash, storedHash, "Stored hash must NOT equal tampered hash (triggers 409 Conflict)");
                }
            }
        }
    }

    @Test
    @DisplayName("Test 4: Concurrent duplicate requests execute exactly once across multiple threads")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test4_ConcurrentDuplicateRequestsExecuteOnlyOnce() throws Exception {
        String key = "concurrent-key-" + UUID.randomUUID();
        String hash = "concurrent-hash-" + UUID.randomUUID();
        int threadCount = 8;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger wonReservations = new AtomicInteger(0);
        AtomicInteger zeroInserted = new AtomicInteger(0);

        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(); // Synchronize all threads to fire simultaneously
                try (Connection conn = getAppConnection()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                            "VALUES (1, 'TRANSFER', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                            "ON DUPLICATE KEY UPDATE id = id")) {
                        ps.setString(1, key);
                        ps.setString(2, hash);
                        int affected = ps.executeUpdate();
                        if (affected == 1) {
                            wonReservations.incrementAndGet();
                        } else {
                            zeroInserted.incrementAndGet();
                        }
                    }
                }
                return null;
            }));
        }

        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Under MySQL InnoDB unique constraint uq_user_op_key, EXACTLY ONE thread wins the reservation
        assertEquals(1, wonReservations.get(), "Exactly one thread must win the idempotency reservation");
        assertEquals(threadCount - 1, zeroInserted.get(), "All other threads must receive 0 rows affected");
    }

    @Test
    @DisplayName("Test 5: Failed unexpected execution rolls back the reservation so a retry succeeds")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test5_FailedUnexpectedExecutionRollsBackReservation() throws SQLException {
        String key = "rollback-key-" + UUID.randomUUID();
        String hash = "rollback-hash-" + UUID.randomUUID();

        // Transaction 1: Starts, reserves key, but fails and rolls back
        try (Connection conn1 = getAppConnection()) {
            conn1.setAutoCommit(false);

            try (PreparedStatement ps = conn1.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (1, 'WITHDRAWAL', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, key);
                ps.setString(2, hash);
                int affected = ps.executeUpdate();
                assertEquals(1, affected);
            }

            // Simulate unexpected failure during business execution -> ROLLBACK
            conn1.rollback();
        }

        // Verify key is NOT in database
        try (Connection conn2 = getAppConnection()) {
            try (PreparedStatement ps = conn2.prepareStatement(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE user_id = 1 AND operation_type = 'WITHDRAWAL' AND idempotency_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "Rolled back reservation must leave zero rows in database");
                }
            }

            // Retry attempt: Can now successfully reserve and complete!
            try (PreparedStatement ps = conn2.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (1, 'WITHDRAWAL', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, key);
                ps.setString(2, hash);
                int retryAffected = ps.executeUpdate();
                assertEquals(1, retryAffected, "Subsequent retry must successfully win the reservation");
            }
        }
    }

    @Test
    @DisplayName("Test 6: Refused business outcome (HTTP 422) is cached and replayed identically")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test6_RefusedBusinessOutcomeCachedAndReplayed() throws SQLException {
        String key = "refusal-key-" + UUID.randomUUID();
        String hash = "refusal-hash-" + UUID.randomUUID();
        String refusalResponse = "{\"status\":422,\"error\":\"INSUFFICIENT_FUNDS\",\"message\":\"Account balance is insufficient\"}";

        try (Connection conn = getAppConnection()) {
            // 1. First attempt: records refusal in idempotency_keys
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, response_status, response_body, expires_at) " +
                    "VALUES (1, 'WITHDRAWAL', ?, ?, 'COMPLETED', 422, ?, NOW() + INTERVAL 1 DAY)")) {
                ps.setString(1, key);
                ps.setString(2, hash);
                ps.setString(3, refusalResponse);
                ps.executeUpdate();
            }

            // 2. Retry attempt: reads cached refusal
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, response_status, response_body FROM idempotency_keys WHERE user_id = 1 AND operation_type = 'WITHDRAWAL' AND idempotency_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("COMPLETED", rs.getString("status"));
                    assertEquals(422, rs.getInt("response_status"));
                    assertEquals(refusalResponse, rs.getString("response_body"));
                }
            }
        }
    }

    @Test
    @DisplayName("Test 7: Different users can use the same idempotency key independently")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test7_DifferentUsersUseSameKeyIndependently() throws SQLException {
        String sharedKey = "shared-user-key-" + UUID.randomUUID();
        String hash1 = "hash-user-1";
        String hash2 = "hash-user-2";

        try (Connection conn = getAppConnection()) {
            // User 1 uses key
            int user1Insert;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (1, 'TRANSFER', ?, ?, 'COMPLETED', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, sharedKey);
                ps.setString(2, hash1);
                user1Insert = ps.executeUpdate();
            }

            // User 2 uses the exact same key
            int user2Insert;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (2, 'TRANSFER', ?, ?, 'COMPLETED', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, sharedKey);
                ps.setString(2, hash2);
                user2Insert = ps.executeUpdate();
            }

            assertEquals(1, user1Insert, "User 1 must insert successfully");
            assertEquals(1, user2Insert, "User 2 must insert successfully with the same key");

            // Verify both rows exist distinctly
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?")) {
                ps.setString(1, sharedKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "Database must contain 2 distinct rows for the same key across 2 users");
                }
            }
        }
    }

    @Test
    @DisplayName("Test 8: Different operation types can use the same idempotency key independently")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test8_DifferentOperationTypesUseSameKeyIndependently() throws SQLException {
        String sharedKey = "shared-op-key-" + UUID.randomUUID();

        try (Connection conn = getAppConnection()) {
            // Same user, operation 1: DEPOSIT
            int op1Insert;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (1, 'DEPOSIT', ?, 'hash-dep', 'COMPLETED', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, sharedKey);
                op1Insert = ps.executeUpdate();
            }

            // Same user, operation 2: WITHDRAWAL
            int op2Insert;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (1, 'WITHDRAWAL', ?, 'hash-with', 'COMPLETED', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, sharedKey);
                op2Insert = ps.executeUpdate();
            }

            assertEquals(1, op1Insert, "DEPOSIT operation must insert successfully");
            assertEquals(1, op2Insert, "WITHDRAWAL operation must insert successfully with the same key");

            // Verify both distinct rows exist
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE user_id = 1 AND idempotency_key = ?")) {
                ps.setString(1, sharedKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "Database must contain 2 distinct rows for the same user & key across 2 operation types");
                }
            }
        }
    }

    @Test
    @DisplayName("Test 9: Invalid foreign key user_id raises SQLException (error 1452) and is not suppressed")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test9_InvalidForeignKeyConstraintRaisesSqlException() throws SQLException {
        String key = "test-key-fk-" + UUID.randomUUID();
        String hash = "hash-fk-" + UUID.randomUUID();
        long nonExistentUserId = 999999L;

        try (Connection conn = getAppConnection()) {
            SQLException ex = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                        "VALUES (?, 'TRANSFER', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                        "ON DUPLICATE KEY UPDATE id = id")) {
                    ps.setLong(1, nonExistentUserId);
                    ps.setString(2, key);
                    ps.setString(3, hash);
                    ps.executeUpdate();
                }
            });

            assertEquals(1452, ex.getErrorCode(), "Foreign key violation must yield MySQL error code 1452");
            assertEquals("23000", ex.getSQLState(), "Foreign key violation must yield SQLState 23000");

            // Verify that no record was created in the database
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?")) {
                ps.setLong(1, nonExistentUserId);
                ps.setString(2, key);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "No record must exist for invalid foreign key");
                }
            }
        }
    }

    @Test
    @DisplayName("Test 10: Invalid check constraint raises SQLException (error 3819) and is not suppressed")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test10_InvalidCheckConstraintRaisesSqlException() throws SQLException {
        String key = "test-key-chk-" + UUID.randomUUID();
        String hash = "hash-chk-" + UUID.randomUUID();

        try (Connection conn = getAppConnection()) {
            SQLException ex = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                        "VALUES (1, 'TRANSFER', ?, ?, 'INVALID_STATUS', NOW() + INTERVAL 1 DAY) " +
                        "ON DUPLICATE KEY UPDATE id = id")) {
                    ps.setString(1, key);
                    ps.setString(2, hash);
                    ps.executeUpdate();
                }
            });

            assertEquals(3819, ex.getErrorCode(), "Check constraint violation must yield MySQL error code 3819");
            assertEquals("HY000", ex.getSQLState(), "Check constraint violation must yield SQLState HY000");

            // Verify that no record exists
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM idempotency_keys WHERE user_id = 1 AND idempotency_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "No record must exist for invalid check constraint");
                }
            }
        }
    }

    @Test
    @DisplayName("Test 11: Concurrent request blocks on InnoDB row lock until commit, then replays completed result")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test11_ConcurrentBlockingAndCommitReplay() throws Exception {
        String key = "block-commit-key-" + UUID.randomUUID();
        String hash = "block-commit-hash-" + UUID.randomUUID();
        String responsePayload = "{\"status\":\"POSTED\",\"txId\":\"tx-commit-11\"}";

        CountDownLatch thread1Inserted = new CountDownLatch(1);
        CountDownLatch thread2Started = new CountDownLatch(1);
        AtomicInteger thread2AffectedRows = new AtomicInteger(-1);
        CountDownLatch thread2Finished = new CountDownLatch(1);

        // Thread 2 (concurrent request)
        Thread thread2 = new Thread(() -> {
            try {
                thread2Started.countDown();
                thread1Inserted.await(5, TimeUnit.SECONDS);

                try (Connection conn2 = getAppConnection()) {
                    // This executeUpdate will block on Thread 1's uncommitted row lock
                    try (PreparedStatement ps = conn2.prepareStatement(
                            "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                            "VALUES (1, 'TRANSFER', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                            "ON DUPLICATE KEY UPDATE id = id")) {
                        ps.setString(1, key);
                        ps.setString(2, hash);
                        int affected = ps.executeUpdate();
                        thread2AffectedRows.set(affected);
                    }

                    // Verify Thread 2 sees the completed row
                    try (PreparedStatement ps = conn2.prepareStatement(
                            "SELECT status, response_status, response_body FROM idempotency_keys " +
                            "WHERE user_id = 1 AND operation_type = 'TRANSFER' AND idempotency_key = ?")) {
                        ps.setString(1, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            assertTrue(rs.next());
                            assertEquals("COMPLETED", rs.getString("status"));
                            assertEquals(200, rs.getInt("response_status"));
                            assertEquals(responsePayload, rs.getString("response_body"));
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                thread2Finished.countDown();
            }
        });

        // Thread 1 (primary request)
        try (Connection conn1 = getAppConnection()) {
            conn1.setAutoCommit(false);

            // Step 1: Thread 1 reserves key
            try (PreparedStatement ps = conn1.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (1, 'TRANSFER', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, key);
                ps.setString(2, hash);
                int affected = ps.executeUpdate();
                assertEquals(1, affected);
            }

            thread1Inserted.countDown();
            thread2.start();
            thread2Started.await(5, TimeUnit.SECONDS);

            // Simulate financial transaction execution time while holding uncommitted lock
            Thread.sleep(300);

            // Step 2: Thread 1 updates to COMPLETED and commits
            try (PreparedStatement ps = conn1.prepareStatement(
                    "UPDATE idempotency_keys SET status = 'COMPLETED', response_status = 200, " +
                    "response_body = ?, completed_at = NOW() " +
                    "WHERE user_id = 1 AND operation_type = 'TRANSFER' AND idempotency_key = ?")) {
                ps.setString(1, responsePayload);
                ps.setString(2, key);
                int updated = ps.executeUpdate();
                assertEquals(1, updated);
            }

            conn1.commit();
        }

        assertTrue(thread2Finished.await(5, TimeUnit.SECONDS), "Thread 2 must complete within timeout after commit");
        assertEquals(0, thread2AffectedRows.get(), "Thread 2 must receive 0 affected rows (ON DUPLICATE KEY UPDATE id = id no-op)");
    }

    @Test
    @DisplayName("Test 12: Concurrent request blocks on InnoDB row lock until rollback, then becomes executor")
    @EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
    void test12_ConcurrentBlockingAndRollbackExecutor() throws Exception {
        String key = "block-rollback-key-" + UUID.randomUUID();
        String hash = "block-rollback-hash-" + UUID.randomUUID();

        CountDownLatch thread1Inserted = new CountDownLatch(1);
        CountDownLatch thread2Started = new CountDownLatch(1);
        AtomicInteger thread2AffectedRows = new AtomicInteger(-1);
        CountDownLatch thread2Finished = new CountDownLatch(1);

        // Thread 2 (concurrent request)
        Thread thread2 = new Thread(() -> {
            try {
                thread2Started.countDown();
                thread1Inserted.await(5, TimeUnit.SECONDS);

                try (Connection conn2 = getAppConnection()) {
                    conn2.setAutoCommit(false);

                    // This executeUpdate will block until Thread 1 rolls back
                    try (PreparedStatement ps = conn2.prepareStatement(
                            "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                            "VALUES (1, 'TRANSFER', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                            "ON DUPLICATE KEY UPDATE id = id")) {
                        ps.setString(1, key);
                        ps.setString(2, hash);
                        int affected = ps.executeUpdate();
                        thread2AffectedRows.set(affected);
                    }

                    // Thread 2 won reservation! Complete operation
                    try (PreparedStatement ps = conn2.prepareStatement(
                            "UPDATE idempotency_keys SET status = 'COMPLETED', response_status = 200, " +
                            "response_body = '{\"status\":\"POSTED\",\"txId\":\"tx-winner-12\"}', completed_at = NOW() " +
                            "WHERE user_id = 1 AND operation_type = 'TRANSFER' AND idempotency_key = ?")) {
                        ps.setString(1, key);
                        int updated = ps.executeUpdate();
                        assertEquals(1, updated);
                    }

                    conn2.commit();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                thread2Finished.countDown();
            }
        });

        // Thread 1 (primary request that will fail and rollback)
        try (Connection conn1 = getAppConnection()) {
            conn1.setAutoCommit(false);

            // Step 1: Thread 1 reserves key
            try (PreparedStatement ps = conn1.prepareStatement(
                    "INSERT INTO idempotency_keys (user_id, operation_type, idempotency_key, request_hash, status, expires_at) " +
                    "VALUES (1, 'TRANSFER', ?, ?, 'IN_PROGRESS', NOW() + INTERVAL 1 DAY) " +
                    "ON DUPLICATE KEY UPDATE id = id")) {
                ps.setString(1, key);
                ps.setString(2, hash);
                int affected = ps.executeUpdate();
                assertEquals(1, affected);
            }

            thread1Inserted.countDown();
            thread2.start();
            thread2Started.await(5, TimeUnit.SECONDS);

            // Simulate unexpected failure while Thread 2 is blocked on row lock
            Thread.sleep(300);

            // Thread 1 rolls back!
            conn1.rollback();
        }

        assertTrue(thread2Finished.await(5, TimeUnit.SECONDS), "Thread 2 must complete within timeout after rollback");
        assertEquals(1, thread2AffectedRows.get(), "Thread 2 must win reservation (1 affected row) after Thread 1 rolled back");

        // Verify the database now contains Thread 2's completed record
        try (Connection verifyConn = getAppConnection()) {
            try (PreparedStatement ps = verifyConn.prepareStatement(
                    "SELECT status, response_body FROM idempotency_keys " +
                    "WHERE user_id = 1 AND operation_type = 'TRANSFER' AND idempotency_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("COMPLETED", rs.getString("status"));
                    assertTrue(rs.getString("response_body").contains("tx-winner-12"));
                }
            }
        }
    }
}
