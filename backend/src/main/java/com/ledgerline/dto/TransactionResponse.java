package com.ledgerline.dto;

import com.ledgerline.entity.Transaction;
import com.ledgerline.entity.TransactionStatus;
import com.ledgerline.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Safe, client-facing transaction response DTO.
 * Exposes financial transaction identifiers, accounts, amounts, and statuses
 * without exposing password hashes, internal security data, or entity graphs.
 */
public class TransactionResponse {

    private String transactionId;
    private String referenceId;
    private Long initiatingUserId;
    private Long sourceAccountId;
    private String sourceAccountNumber;
    private Long destinationAccountId;
    private String destinationAccountNumber;
    private BigDecimal amount;
    private String currency;
    private TransactionType type;
    private TransactionStatus status;
    private String refusalReason;
    private String description;
    private Instant createdAt;
    private Instant completedAt;

    public TransactionResponse() {}

    public TransactionResponse(
            String transactionId,
            String referenceId,
            Long initiatingUserId,
            Long sourceAccountId,
            String sourceAccountNumber,
            Long destinationAccountId,
            String destinationAccountNumber,
            BigDecimal amount,
            String currency,
            TransactionType type,
            TransactionStatus status,
            String refusalReason,
            String description,
            Instant createdAt,
            Instant completedAt) {
        this.transactionId = transactionId;
        this.referenceId = referenceId;
        this.initiatingUserId = initiatingUserId;
        this.sourceAccountId = sourceAccountId;
        this.sourceAccountNumber = sourceAccountNumber;
        this.destinationAccountId = destinationAccountId;
        this.destinationAccountNumber = destinationAccountNumber;
        this.amount = amount;
        this.currency = currency;
        this.type = type;
        this.status = status;
        this.refusalReason = refusalReason;
        this.description = description;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public static TransactionResponse fromEntity(Transaction transaction) {
        if (transaction == null) {
            return null;
        }
        return new TransactionResponse(
                transaction.getId(),
                transaction.getReferenceId(),
                transaction.getInitiatingUser() != null ? transaction.getInitiatingUser().getId() : null,
                transaction.getSourceAccount() != null ? transaction.getSourceAccount().getId() : null,
                transaction.getSourceAccount() != null ? transaction.getSourceAccount().getAccountNumber() : null,
                transaction.getDestinationAccount() != null ? transaction.getDestinationAccount().getId() : null,
                transaction.getDestinationAccount() != null ? transaction.getDestinationAccount().getAccountNumber() : null,
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getRefusalReason(),
                transaction.getDescription(),
                transaction.getCreatedAt(),
                transaction.getCompletedAt()
        );
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public Long getInitiatingUserId() {
        return initiatingUserId;
    }

    public void setInitiatingUserId(Long initiatingUserId) {
        this.initiatingUserId = initiatingUserId;
    }

    public Long getSourceAccountId() {
        return sourceAccountId;
    }

    public void setSourceAccountId(Long sourceAccountId) {
        this.sourceAccountId = sourceAccountId;
    }

    public String getSourceAccountNumber() {
        return sourceAccountNumber;
    }

    public void setSourceAccountNumber(String sourceAccountNumber) {
        this.sourceAccountNumber = sourceAccountNumber;
    }

    public Long getDestinationAccountId() {
        return destinationAccountId;
    }

    public void setDestinationAccountId(Long destinationAccountId) {
        this.destinationAccountId = destinationAccountId;
    }

    public String getDestinationAccountNumber() {
        return destinationAccountNumber;
    }

    public void setDestinationAccountNumber(String destinationAccountNumber) {
        this.destinationAccountNumber = destinationAccountNumber;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getRefusalReason() {
        return refusalReason;
    }

    public void setRefusalReason(String refusalReason) {
        this.refusalReason = refusalReason;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
