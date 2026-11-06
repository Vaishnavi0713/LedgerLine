package com.ledgerline.dto;

import com.ledgerline.entity.StatementEvent;
import com.ledgerline.entity.StatementEventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Client-facing statement event response DTO.
 * Exposes account statement history events with monotonic sequence numbers,
 * amounts, running balance, and status/reason.
 */
public class StatementEventResponse {

    private Long id;
    private Long accountId;
    private String accountNumber;
    private Long sequenceNumber;
    private String transactionId;
    private StatementEventType eventType;
    private BigDecimal amount;
    private BigDecimal runningBalance;
    private String status;
    private String reason;
    private Instant createdAt;

    public StatementEventResponse() {}

    public StatementEventResponse(
            Long id,
            Long accountId,
            String accountNumber,
            Long sequenceNumber,
            String transactionId,
            StatementEventType eventType,
            BigDecimal amount,
            BigDecimal runningBalance,
            String status,
            String reason,
            Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.sequenceNumber = sequenceNumber;
        this.transactionId = transactionId;
        this.eventType = eventType;
        this.amount = amount;
        this.runningBalance = runningBalance;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public static StatementEventResponse fromEntity(StatementEvent event) {
        if (event == null) {
            return null;
        }
        return new StatementEventResponse(
                event.getId(),
                event.getAccount() != null ? event.getAccount().getId() : null,
                event.getAccount() != null ? event.getAccount().getAccountNumber() : null,
                event.getSequenceNumber(),
                event.getTransactionId(),
                event.getEventType(),
                event.getAmount(),
                event.getRunningBalance(),
                event.getStatus(),
                event.getReason(),
                event.getCreatedAt()
        );
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(Long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public StatementEventType getEventType() {
        return eventType;
    }

    public void setEventType(StatementEventType eventType) {
        this.eventType = eventType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getRunningBalance() {
        return runningBalance;
    }

    public void setRunningBalance(BigDecimal runningBalance) {
        this.runningBalance = runningBalance;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
