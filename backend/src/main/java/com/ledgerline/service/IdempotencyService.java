package com.ledgerline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ledgerline.dto.IdempotentExecutionResult;
import com.ledgerline.entity.IdempotencyKey;
import com.ledgerline.entity.IdempotencyStatus;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final int DEFAULT_EXPIRATION_HOURS = 24;

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyKeyRepository idempotencyKeyRepository, ObjectMapper objectMapper) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    }

    /**
     * Executes an operation with strict end-to-end idempotency semantics.
     * Shares the current transaction context so that unexpected failures roll back
     * both the idempotency reservation and all financial database changes.
     */
    @Transactional(rollbackFor = Exception.class)
    public <T> IdempotentExecutionResult<T> executeIdempotent(
            Long userId,
            String operationType,
            String idempotencyKey,
            Object requestPayload,
            Class<T> responseType,
            Supplier<IdempotentExecutionResult<T>> operation) {

        validateParameters(userId, operationType, idempotencyKey);

        String requestHash = computePayloadHash(requestPayload);
        Instant expiresAt = Instant.now().plus(DEFAULT_EXPIRATION_HOURS, ChronoUnit.HOURS);

        // 1. Attempt atomic reservation via INSERT ... ON DUPLICATE KEY UPDATE in the current transaction
        int inserted = idempotencyKeyRepository.insertIfNotExists(
                userId, operationType, idempotencyKey, requestHash, IdempotencyStatus.IN_PROGRESS.name(), expiresAt);

        if (inserted == 1) {
            log.debug("Successfully reserved idempotency key: user={}, op={}, key={}", userId, operationType, idempotencyKey);
            // Reservation won: execute the financial operation
            IdempotentExecutionResult<T> result = operation.get();

            // Cache the result (whether successful 200/201 or business refusal 422)
            String responseJson = serializeResponse(result.body());
            idempotencyKeyRepository.updateStatusAndResponse(
                    userId, operationType, idempotencyKey,
                    IdempotencyStatus.COMPLETED, result.statusCode(), responseJson, Instant.now());

            return new IdempotentExecutionResult<>(false, result.statusCode(), result.body());
        }

        // 2. Key was already inserted by a prior or concurrent transaction
        log.debug("Idempotency key already exists, resolving existing state: user={}, op={}, key={}", userId, operationType, idempotencyKey);
        return handleExistingKey(userId, operationType, idempotencyKey, requestHash, responseType);
    }

    /**
     * Resolves an existing idempotency record:
     * - Replays cached response on exact hash match if COMPLETED.
     * - Throws HTTP 409 Conflict if payload hash differs.
     * - Rejects with HTTP 409 Conflict if still IN_PROGRESS.
     */
    private <T> IdempotentExecutionResult<T> handleExistingKey(
            Long userId,
            String operationType,
            String idempotencyKey,
            String currentRequestHash,
            Class<T> responseType) {

        Optional<IdempotencyKey> existingOpt = idempotencyKeyRepository
                .findByUserIdAndOperationTypeAndIdempotencyKey(userId, operationType, idempotencyKey);

        if (existingOpt.isEmpty()) {
            throw new IllegalStateException(
                    "Idempotency duplicate key conflict detected, but existing record could not be found for key '" + idempotencyKey + "'.");
        }

        IdempotencyKey existing = existingOpt.get();

        // Detect payload mismatch
        if (!existing.getRequestHash().equals(currentRequestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key '" + idempotencyKey + "' was already used for a different request payload.");
        }

        // If already completed, replay the original cached response
        if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
            log.info("Replaying cached response for idempotency key: user={}, op={}, key={}", userId, operationType, idempotencyKey);
            T cachedBody = deserializeResponse(existing.getResponseBody(), responseType);
            return new IdempotentExecutionResult<>(true, existing.getResponseStatus(), cachedBody);
        }

        // In this single-transaction architecture, the initial reservation and completion
        // occur within the same transaction. An uncommitted IN_PROGRESS state blocks concurrent
        // requests via InnoDB unique index locks until commit or rollback.
        // If an IN_PROGRESS status is ever observed on an existing committed record, reject with conflict.
        if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new IdempotencyConflictException(
                    "A concurrent operation with idempotency key '" + idempotencyKey + "' is currently in progress. Please retry shortly.");
        }

        throw new IdempotencyConflictException(
                "Idempotency key '" + idempotencyKey + "' is in an invalid state: " + existing.getStatus());
    }

    /**
     * Computes a deterministic SHA-256 hash of the request payload.
     * Maps, collections, nested objects, and JSON strings are canonicalized with sorted keys.
     */
    public String computePayloadHash(Object payload) {
        if (payload == null) {
            return hashBytes(new byte[0]);
        }
        if (payload instanceof String s) {
            String trimmed = s.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    Object parsed = objectMapper.readValue(trimmed, Object.class);
                    byte[] canonicalBytes = objectMapper.writeValueAsBytes(parsed);
                    return hashBytes(canonicalBytes);
                } catch (Exception ignored) {
                    // Not valid JSON, fallback to raw string bytes below
                }
            }
            return hashBytes(trimmed.getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
            return hashBytes(jsonBytes);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize request payload for idempotency hashing", e);
        }
    }

    private String hashBytes(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in runtime environment", e);
        }
    }

    private String serializeResponse(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize response body for idempotency caching", e);
        }
    }

    private <T> T deserializeResponse(String json, Class<T> responseType) {
        if (json == null || responseType.equals(Void.class)) {
            return null;
        }
        if (responseType.equals(String.class)) {
            return responseType.cast(json);
        }
        try {
            return objectMapper.readValue(json, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached idempotency response", e);
        }
    }

    private void validateParameters(Long userId, String operationType, String idempotencyKey) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null for idempotency tracking.");
        }
        if (operationType == null || operationType.isBlank()) {
            throw new IllegalArgumentException("Operation type must not be null or blank.");
        }
        if (operationType.length() > 32) {
            throw new IllegalArgumentException("Operation type length must not exceed 32 characters.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be null or blank.");
        }
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency key length must not exceed 128 characters.");
        }
    }
}
