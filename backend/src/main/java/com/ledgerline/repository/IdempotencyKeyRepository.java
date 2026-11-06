package com.ledgerline.repository;

import com.ledgerline.entity.IdempotencyKey;
import com.ledgerline.entity.IdempotencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndOperationTypeAndIdempotencyKey(
            Long userId, String operationType, String idempotencyKey);

    /**
     * Atomically attempts to reserve an idempotency key using MySQL's
     * INSERT ... ON DUPLICATE KEY UPDATE id = id.
     *
     * With MySQL Connector/J configured with useAffectedRows=true:
     * - Returns 1 when a new row is successfully inserted (reservation won).
     * - Returns 0 when a duplicate key exists on (user_id, operation_type, idempotency_key),
     *   as id = id results in 0 changed/affected rows.
     *
     * Unlike INSERT IGNORE, database errors such as foreign key violations (fk_idem_user),
     * check constraint violations (chk_idem_status), NOT NULL violations, and data truncation
     * are NOT suppressed and propagate normally as genuine database exceptions.
     *
     * @return 1 if row was inserted (reservation won), 0 if duplicate key already exists.
     */
    @Modifying
    @Query(value = "INSERT INTO idempotency_keys " +
                   "(user_id, operation_type, idempotency_key, request_hash, status, expires_at, created_at) " +
                   "VALUES (:userId, :operationType, :idempotencyKey, :requestHash, :status, :expiresAt, CURRENT_TIMESTAMP) " +
                   "ON DUPLICATE KEY UPDATE id = id",
           nativeQuery = true)
    int insertIfNotExists(
            @Param("userId") Long userId,
            @Param("operationType") String operationType,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("status") String status,
            @Param("expiresAt") Instant expiresAt);

    /**
     * Updates an in-progress idempotency reservation to completed or failed state.
     */
    @Modifying
    @Query("UPDATE IdempotencyKey k SET k.status = :status, k.responseStatus = :responseStatus, " +
           "k.responseBody = :responseBody, k.completedAt = :completedAt " +
           "WHERE k.user.id = :userId AND k.operationType = :operationType AND k.idempotencyKey = :idempotencyKey")
    int updateStatusAndResponse(
            @Param("userId") Long userId,
            @Param("operationType") String operationType,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("status") IdempotencyStatus status,
            @Param("responseStatus") Integer responseStatus,
            @Param("responseBody") String responseBody,
            @Param("completedAt") Instant completedAt);
}
