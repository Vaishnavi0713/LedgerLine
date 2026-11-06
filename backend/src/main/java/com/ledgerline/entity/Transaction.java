package com.ledgerline.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "reference_id", nullable = false, unique = true, length = 64)
    private String referenceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiating_user_id", nullable = false)
    private User initiatingUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false)
    private Account destinationAccount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(Types.CHAR)
    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "refusal_reason")
    private String refusalReason;

    @Column(length = 255)
    private String description;

    @Column(name = "original_transaction_id", length = 36)
    private String originalTransactionId;

    @Column(name = "reversal_target_id", length = 36, insertable = false, updatable = false)
    private String reversalTargetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public Transaction() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public User getInitiatingUser() { return initiatingUser; }
    public void setInitiatingUser(User initiatingUser) { this.initiatingUser = initiatingUser; }

    public Account getSourceAccount() { return sourceAccount; }
    public void setSourceAccount(Account sourceAccount) { this.sourceAccount = sourceAccount; }

    public Account getDestinationAccount() { return destinationAccount; }
    public void setDestinationAccount(Account destinationAccount) { this.destinationAccount = destinationAccount; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }

    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }

    public String getRefusalReason() { return refusalReason; }
    public void setRefusalReason(String refusalReason) { this.refusalReason = refusalReason; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getOriginalTransactionId() { return originalTransactionId; }
    public void setOriginalTransactionId(String originalTransactionId) { this.originalTransactionId = originalTransactionId; }

    public String getReversalTargetId() { return reversalTargetId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
