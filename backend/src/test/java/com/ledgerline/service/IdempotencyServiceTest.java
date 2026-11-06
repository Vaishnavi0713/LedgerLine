package com.ledgerline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.dto.IdempotentExecutionResult;
import com.ledgerline.entity.IdempotencyKey;
import com.ledgerline.entity.IdempotencyStatus;
import com.ledgerline.entity.User;
import com.ledgerline.entity.UserRole;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private IdempotencyService idempotencyService;
    private ObjectMapper objectMapper;

    // Test payload record
    record TestTransferPayload(Long sourceAccountId, Long destAccountId, BigDecimal amount, String description) {}
    record TestResponse(String transactionId, String status, BigDecimal amount) {}

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        idempotencyService = new IdempotencyService(idempotencyKeyRepository, objectMapper);
    }

    @Test
    @DisplayName("Deterministic Hashing: Identical objects with same data yield identical 64-char SHA-256 hex")
    void testDeterministicHashing() {
        TestTransferPayload payload1 = new TestTransferPayload(101L, 202L, new BigDecimal("500.00"), "Monthly rent");
        TestTransferPayload payload2 = new TestTransferPayload(101L, 202L, new BigDecimal("500.00"), "Monthly rent");
        TestTransferPayload differentPayload = new TestTransferPayload(101L, 202L, new BigDecimal("500.01"), "Monthly rent");

        String hash1 = idempotencyService.computePayloadHash(payload1);
        String hash2 = idempotencyService.computePayloadHash(payload2);
        String hashDiff = idempotencyService.computePayloadHash(differentPayload);

        assertNotNull(hash1);
        assertEquals(64, hash1.length(), "SHA-256 hex must be 64 characters");
        assertEquals(hash1, hash2, "Identical payloads must yield identical hash");
        assertNotEquals(hash1, hashDiff, "Different amounts must yield different hash");
    }

    @Test
    @DisplayName("Deterministic Hashing: Maps with different key insertion order yield identical SHA-256 hash")
    void testDeterministicHashingWithMapKeyOrdering() {
        Map<String, Object> map1 = new LinkedHashMap<>();
        map1.put("description", "Transfer to savings");
        map1.put("amount", new BigDecimal("250.00"));
        map1.put("sourceAccountId", 101L);
        map1.put("destAccountId", 202L);

        // Reverse insertion order
        Map<String, Object> map2 = new LinkedHashMap<>();
        map2.put("destAccountId", 202L);
        map2.put("sourceAccountId", 101L);
        map2.put("amount", new BigDecimal("250.00"));
        map2.put("description", "Transfer to savings");

        String hash1 = idempotencyService.computePayloadHash(map1);
        String hash2 = idempotencyService.computePayloadHash(map2);

        assertEquals(hash1, hash2, "Maps with identical entries in different insertion orders must yield identical hash");
    }

    @Test
    @DisplayName("Deterministic Hashing: Nested maps with different property ordering yield identical SHA-256 hash")
    void testDeterministicHashingWithNestedMapOrdering() {
        Map<String, Object> inner1 = new LinkedHashMap<>();
        inner1.put("zip", "560001");
        inner1.put("city", "Bengaluru");
        inner1.put("state", "KA");

        Map<String, Object> outer1 = new LinkedHashMap<>();
        outer1.put("accountHolder", "Alice");
        outer1.put("address", inner1);
        outer1.put("accountId", 555L);

        // Different order at both outer and nested levels
        Map<String, Object> inner2 = new LinkedHashMap<>();
        inner2.put("state", "KA");
        inner2.put("city", "Bengaluru");
        inner2.put("zip", "560001");

        Map<String, Object> outer2 = new LinkedHashMap<>();
        outer2.put("accountId", 555L);
        outer2.put("address", inner2);
        outer2.put("accountHolder", "Alice");

        String hash1 = idempotencyService.computePayloadHash(outer1);
        String hash2 = idempotencyService.computePayloadHash(outer2);

        assertEquals(hash1, hash2, "Nested maps with differing property orders must yield identical canonical hash");
    }

    @Test
    @DisplayName("Deterministic Hashing: JSON strings with different property order yield identical SHA-256 hash")
    void testDeterministicHashingWithJsonStringOrdering() {
        String json1 = "{\"amount\": 100.00, \"description\": \"Lunch\", \"recipient\": \"Bob\"}";
        String json2 = "{\"recipient\": \"Bob\", \"description\": \"Lunch\", \"amount\": 100.00}";

        String hash1 = idempotencyService.computePayloadHash(json1);
        String hash2 = idempotencyService.computePayloadHash(json2);

        assertEquals(hash1, hash2, "JSON strings with different property order must yield identical hash");
    }

    @Test
    @DisplayName("Deterministic Hashing: Different payloads produce different SHA-256 hashes")
    void testDifferentPayloadsProduceDifferentHashes() {
        Map<String, Object> base = Map.of("account", 101L, "amount", new BigDecimal("100.00"));
        Map<String, Object> differentAmount = Map.of("account", 101L, "amount", new BigDecimal("100.01"));
        Map<String, Object> differentAccount = Map.of("account", 102L, "amount", new BigDecimal("100.00"));

        String hashBase = idempotencyService.computePayloadHash(base);
        String hashDiffAmount = idempotencyService.computePayloadHash(differentAmount);
        String hashDiffAccount = idempotencyService.computePayloadHash(differentAccount);

        assertNotEquals(hashBase, hashDiffAmount);
        assertNotEquals(hashBase, hashDiffAccount);
    }

    @Test
    @DisplayName("Scenario 1: First request successfully reserves key and executes operation")
    void testFirstRequestReservesAndExecutes() {
        Long userId = 1L;
        String op = "TRANSFER";
        String key = "test-key-101";
        TestTransferPayload payload = new TestTransferPayload(1L, 2L, new BigDecimal("100.00"), "Transfer 1");
        AtomicInteger executions = new AtomicInteger(0);

        when(idempotencyKeyRepository.insertIfNotExists(eq(userId), eq(op), eq(key), anyString(), eq("IN_PROGRESS"), any()))
                .thenReturn(1);
        when(idempotencyKeyRepository.updateStatusAndResponse(eq(userId), eq(op), eq(key), eq(IdempotencyStatus.COMPLETED), eq(200), anyString(), any()))
                .thenReturn(1);

        IdempotentExecutionResult<TestResponse> result = idempotencyService.executeIdempotent(
                userId, op, key, payload, TestResponse.class, () -> {
                    executions.incrementAndGet();
                    return new IdempotentExecutionResult<>(false, 200, new TestResponse("tx-001", "POSTED", new BigDecimal("100.00")));
                });

        assertFalse(result.replayed(), "First execution must not be marked replayed");
        assertEquals(200, result.statusCode());
        assertEquals("tx-001", result.body().transactionId());
        assertEquals(1, executions.get(), "Operation must execute exactly once");

        verify(idempotencyKeyRepository, times(1)).insertIfNotExists(eq(userId), eq(op), eq(key), anyString(), eq("IN_PROGRESS"), any());
        verify(idempotencyKeyRepository, times(1)).updateStatusAndResponse(eq(userId), eq(op), eq(key), eq(IdempotencyStatus.COMPLETED), eq(200), anyString(), any());
    }

    @Test
    @DisplayName("Scenario 2: Same key and same payload returns original cached response without re-executing")
    void testSameKeyAndPayloadReplaysOriginalResponse() {
        Long userId = 1L;
        String op = "TRANSFER";
        String key = "test-key-102";
        TestTransferPayload payload = new TestTransferPayload(1L, 2L, new BigDecimal("100.00"), "Transfer 2");
        String hash = idempotencyService.computePayloadHash(payload);

        IdempotencyKey completedKey = new IdempotencyKey();
        completedKey.setStatus(IdempotencyStatus.COMPLETED);
        completedKey.setRequestHash(hash);
        completedKey.setResponseStatus(200);
        completedKey.setResponseBody("{\"transactionId\":\"tx-original\",\"status\":\"POSTED\",\"amount\":100.00}");

        // insertIfNotExists returns 0 (already exists)
        when(idempotencyKeyRepository.insertIfNotExists(eq(userId), eq(op), eq(key), eq(hash), eq("IN_PROGRESS"), any()))
                .thenReturn(0);
        when(idempotencyKeyRepository.findByUserIdAndOperationTypeAndIdempotencyKey(userId, op, key))
                .thenReturn(Optional.of(completedKey));

        AtomicInteger executions = new AtomicInteger(0);
        IdempotentExecutionResult<TestResponse> result = idempotencyService.executeIdempotent(
                userId, op, key, payload, TestResponse.class, () -> {
                    executions.incrementAndGet();
                    return new IdempotentExecutionResult<>(false, 200, new TestResponse("tx-duplicate", "POSTED", new BigDecimal("100.00")));
                });

        assertTrue(result.replayed(), "Duplicate execution must be marked replayed");
        assertEquals(200, result.statusCode());
        assertEquals("tx-original", result.body().transactionId());
        assertEquals(0, executions.get(), "Operation must NEVER execute when replaying cached response");
    }

    @Test
    @DisplayName("Scenario 3: Same key with different payload throws HTTP 409 Conflict")
    void testSameKeyDifferentPayloadThrowsConflict() {
        Long userId = 1L;
        String op = "TRANSFER";
        String key = "test-key-103";
        TestTransferPayload originalPayload = new TestTransferPayload(1L, 2L, new BigDecimal("100.00"), "Transfer original");
        TestTransferPayload modifiedPayload = new TestTransferPayload(1L, 2L, new BigDecimal("999.00"), "Tampered transfer");

        String originalHash = idempotencyService.computePayloadHash(originalPayload);
        String modifiedHash = idempotencyService.computePayloadHash(modifiedPayload);

        IdempotencyKey existingKey = new IdempotencyKey();
        existingKey.setStatus(IdempotencyStatus.COMPLETED);
        existingKey.setRequestHash(originalHash); // stored with original hash
        existingKey.setResponseStatus(200);

        when(idempotencyKeyRepository.insertIfNotExists(eq(userId), eq(op), eq(key), eq(modifiedHash), eq("IN_PROGRESS"), any()))
                .thenReturn(0);
        when(idempotencyKeyRepository.findByUserIdAndOperationTypeAndIdempotencyKey(userId, op, key))
                .thenReturn(Optional.of(existingKey));

        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class, () -> {
            idempotencyService.executeIdempotent(
                    userId, op, key, modifiedPayload, TestResponse.class,
                    () -> new IdempotentExecutionResult<>(false, 200, new TestResponse("tx-new", "POSTED", new BigDecimal("999.00"))));
        });

        assertTrue(ex.getMessage().contains("already used for a different request payload"));
    }

    @Test
    @DisplayName("Scenario 5: Failed unexpected execution propagates exception so outer transaction can roll back")
    void testUnexpectedFailurePropagatesException() {
        Long userId = 1L;
        String op = "TRANSFER";
        String key = "test-key-105";
        TestTransferPayload payload = new TestTransferPayload(1L, 2L, new BigDecimal("50.00"), "Transfer 5");

        when(idempotencyKeyRepository.insertIfNotExists(eq(userId), eq(op), eq(key), anyString(), eq("IN_PROGRESS"), any()))
                .thenReturn(1);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            idempotencyService.executeIdempotent(
                    userId, op, key, payload, TestResponse.class, () -> {
                        throw new RuntimeException("Simulated database failure during procedure execution");
                    });
        });

        assertEquals("Simulated database failure during procedure execution", ex.getMessage());
        // updateStatusAndResponse must NOT be called on failure so outer transaction rollback clears everything
        verify(idempotencyKeyRepository, never()).updateStatusAndResponse(anyLong(), anyString(), anyString(), any(), anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("Scenario 6: Refused business outcome (HTTP 422) is cached and replayed identically on retry")
    void testRefusedBusinessOutcomeIsCachedAndReplayed() {
        Long userId = 1L;
        String op = "TRANSFER";
        String key = "test-key-106";
        TestTransferPayload payload = new TestTransferPayload(1L, 2L, new BigDecimal("5000.00"), "Overdraft transfer");
        String hash = idempotencyService.computePayloadHash(payload);

        // First attempt: records refusal
        when(idempotencyKeyRepository.insertIfNotExists(eq(userId), eq(op), eq(key), eq(hash), eq("IN_PROGRESS"), any()))
                .thenReturn(1);
        when(idempotencyKeyRepository.updateStatusAndResponse(eq(userId), eq(op), eq(key), eq(IdempotencyStatus.COMPLETED), eq(422), anyString(), any()))
                .thenReturn(1);

        IdempotentExecutionResult<TestResponse> refusedResult = idempotencyService.executeIdempotent(
                userId, op, key, payload, TestResponse.class, () -> {
                    return new IdempotentExecutionResult<>(false, 422, new TestResponse("tx-refused", "REFUSED", new BigDecimal("5000.00")));
                });

        assertFalse(refusedResult.replayed());
        assertEquals(422, refusedResult.statusCode());
        assertEquals("REFUSED", refusedResult.body().status());

        // Second attempt: replays refusal
        IdempotencyKey cachedRefusedKey = new IdempotencyKey();
        cachedRefusedKey.setStatus(IdempotencyStatus.COMPLETED);
        cachedRefusedKey.setRequestHash(hash);
        cachedRefusedKey.setResponseStatus(422);
        cachedRefusedKey.setResponseBody("{\"transactionId\":\"tx-refused\",\"status\":\"REFUSED\",\"amount\":5000.00}");

        when(idempotencyKeyRepository.insertIfNotExists(eq(userId), eq(op), eq(key), eq(hash), eq("IN_PROGRESS"), any()))
                .thenReturn(0);
        when(idempotencyKeyRepository.findByUserIdAndOperationTypeAndIdempotencyKey(userId, op, key))
                .thenReturn(Optional.of(cachedRefusedKey));

        AtomicInteger secondAttemptCalls = new AtomicInteger(0);
        IdempotentExecutionResult<TestResponse> replayResult = idempotencyService.executeIdempotent(
                userId, op, key, payload, TestResponse.class, () -> {
                    secondAttemptCalls.incrementAndGet();
                    return new IdempotentExecutionResult<>(false, 200, new TestResponse("tx-unexpected", "POSTED", new BigDecimal("5000.00")));
                });

        assertTrue(replayResult.replayed());
        assertEquals(422, replayResult.statusCode());
        assertEquals("REFUSED", replayResult.body().status());
        assertEquals(0, secondAttemptCalls.get(), "Must not re-execute financial operation on replaying refusal");
    }

    @Test
    @DisplayName("Validation: Operation type length boundary (32 chars passes, 33 chars rejected)")
    void testOperationTypeValidationBoundaries() {
        String exact32Chars = "12345678901234567890123456789012";
        assertEquals(32, exact32Chars.length());

        String tooLong33Chars = "123456789012345678901234567890123";
        assertEquals(33, tooLong33Chars.length());

        // 32 chars passes validation
        when(idempotencyKeyRepository.insertIfNotExists(eq(1L), eq(exact32Chars), eq("key-32"), anyString(), anyString(), any()))
                .thenReturn(1);
        when(idempotencyKeyRepository.updateStatusAndResponse(eq(1L), eq(exact32Chars), eq("key-32"), any(), anyInt(), any(), any()))
                .thenReturn(1);

        assertDoesNotThrow(() -> idempotencyService.executeIdempotent(
                1L, exact32Chars, "key-32", "payload", String.class,
                () -> new IdempotentExecutionResult<>(false, 200, "OK")));

        // 33 chars throws IllegalArgumentException
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                idempotencyService.executeIdempotent(
                        1L, tooLong33Chars, "key-33", "payload", String.class,
                        () -> new IdempotentExecutionResult<>(false, 200, "OK")));
        assertTrue(ex.getMessage().contains("must not exceed 32 characters"));

        // null and blank operation types
        assertThrows(IllegalArgumentException.class, () ->
                idempotencyService.executeIdempotent(1L, null, "k1", "payload", String.class, () -> null));
        assertThrows(IllegalArgumentException.class, () ->
                idempotencyService.executeIdempotent(1L, "   ", "k1", "payload", String.class, () -> null));
    }

    @Test
    @DisplayName("Validation: Idempotency key length boundary (128 chars passes, 129 chars rejected)")
    void testIdempotencyKeyValidationBoundaries() {
        String exact128Chars = "a".repeat(128);
        String tooLong129Chars = "a".repeat(129);

        // 128 chars passes validation
        when(idempotencyKeyRepository.insertIfNotExists(eq(1L), eq("OP"), eq(exact128Chars), anyString(), anyString(), any()))
                .thenReturn(1);
        when(idempotencyKeyRepository.updateStatusAndResponse(eq(1L), eq("OP"), eq(exact128Chars), any(), anyInt(), any(), any()))
                .thenReturn(1);

        assertDoesNotThrow(() -> idempotencyService.executeIdempotent(
                1L, "OP", exact128Chars, "payload", String.class,
                () -> new IdempotentExecutionResult<>(false, 200, "OK")));

        // 129 chars throws IllegalArgumentException
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                idempotencyService.executeIdempotent(
                        1L, "OP", tooLong129Chars, "payload", String.class,
                        () -> new IdempotentExecutionResult<>(false, 200, "OK")));
        assertTrue(ex.getMessage().contains("must not exceed 128 characters"));

        // null and blank keys
        assertThrows(IllegalArgumentException.class, () ->
                idempotencyService.executeIdempotent(1L, "OP", null, "payload", String.class, () -> null));
        assertThrows(IllegalArgumentException.class, () ->
                idempotencyService.executeIdempotent(1L, "OP", "   ", "payload", String.class, () -> null));
        assertThrows(IllegalArgumentException.class, () ->
                idempotencyService.executeIdempotent(null, "OP", "k1", "payload", String.class, () -> null));
    }

    @Test
    @DisplayName("Error Handling: insertIfNotExists returns 0 but record missing throws IllegalStateException (not 409)")
    void testExistingRecordMissingThrowsIllegalStateException() {
        Long userId = 1L;
        String op = "TRANSFER";
        String key = "test-key-missing";
        String payload = "test payload";
        String hash = idempotencyService.computePayloadHash(payload);

        when(idempotencyKeyRepository.insertIfNotExists(eq(userId), eq(op), eq(key), eq(hash), eq("IN_PROGRESS"), any()))
                .thenReturn(0);
        when(idempotencyKeyRepository.findByUserIdAndOperationTypeAndIdempotencyKey(userId, op, key))
                .thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                idempotencyService.executeIdempotent(userId, op, key, payload, String.class, () -> null));

        assertTrue(ex.getMessage().contains("could not be found for key"));
    }

    @Test
    @DisplayName("Concurrency: Observed IN_PROGRESS status rejects with 409 Conflict without dead polling")
    void testObservedInProgressThrowsConflict() {
        Long userId = 1L;
        String op = "TRANSFER";
        String key = "test-key-in-progress";
        String payload = "test payload";
        String hash = idempotencyService.computePayloadHash(payload);

        IdempotencyKey inProgressKey = new IdempotencyKey();
        inProgressKey.setStatus(IdempotencyStatus.IN_PROGRESS);
        inProgressKey.setRequestHash(hash);

        when(idempotencyKeyRepository.insertIfNotExists(eq(userId), eq(op), eq(key), eq(hash), eq("IN_PROGRESS"), any()))
                .thenReturn(0);
        when(idempotencyKeyRepository.findByUserIdAndOperationTypeAndIdempotencyKey(userId, op, key))
                .thenReturn(Optional.of(inProgressKey));

        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class, () ->
                idempotencyService.executeIdempotent(userId, op, key, payload, String.class, () -> null));

        assertTrue(ex.getMessage().contains("currently in progress. Please retry shortly"));
    }
}
