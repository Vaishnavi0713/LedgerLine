package com.ledgerline.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "statement_events", uniqueConstraints = {
    @UniqueConstraint(name = "uq_account_sequence", columnNames = {"account_id", "sequence_number"})
})
public class StatementEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;

    @Column(name = "initiating_user_id", nullable = false)
    private Long initiatingUserId;

    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private StatementEventType eventType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "running_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal runningBalance;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public StatementEvent() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Account getAccount() { return account; }
    public void setAccount(Account account) { this.account = account; }

    public Long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(Long sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public Long getInitiatingUserId() { return initiatingUserId; }
    public void setInitiatingUserId(Long initiatingUserId) { this.initiatingUserId = initiatingUserId; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public StatementEventType getEventType() { return eventType; }
    public void setEventType(StatementEventType eventType) { this.eventType = eventType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getRunningBalance() { return runningBalance; }
    public void setRunningBalance(BigDecimal runningBalance) { this.runningBalance = runningBalance; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
