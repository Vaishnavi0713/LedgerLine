package com.ledgerline.dto;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.AccountStatus;
import com.ledgerline.entity.AccountType;
import com.ledgerline.entity.StatementEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Client-facing account statement response DTO.
 * Summarizes the account metadata, current balance, and statement event history.
 */
public class AccountStatementResponse {

    private Long accountId;
    private String accountNumber;
    private String name;
    private AccountType accountType;
    private String currency;
    private BigDecimal balance;
    private AccountStatus status;
    private List<StatementEventResponse> events;
    private int totalEvents;
    private Instant asOf;

    public AccountStatementResponse() {}

    public AccountStatementResponse(
            Long accountId,
            String accountNumber,
            String name,
            AccountType accountType,
            String currency,
            BigDecimal balance,
            AccountStatus status,
            List<StatementEventResponse> events,
            int totalEvents,
            Instant asOf) {
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.name = name;
        this.accountType = accountType;
        this.currency = currency;
        this.balance = balance;
        this.status = status;
        this.events = events != null ? events : Collections.emptyList();
        this.totalEvents = totalEvents;
        this.asOf = asOf;
    }

    public static AccountStatementResponse from(Account account, List<StatementEventResponse> eventResponses) {
        if (account == null) {
            return null;
        }
        List<StatementEventResponse> safeEvents = (eventResponses != null)
                ? List.copyOf(eventResponses)
                : Collections.emptyList();
        return new AccountStatementResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getName(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus(),
                safeEvents,
                safeEvents.size(),
                Instant.now()
        );
    }

    public static AccountStatementResponse fromEntities(Account account, List<StatementEvent> eventEntities) {
        if (account == null) {
            return null;
        }
        List<StatementEventResponse> eventResponses = (eventEntities != null)
                ? eventEntities.stream().map(StatementEventResponse::fromEntity).toList()
                : Collections.emptyList();
        return from(account, eventResponses);
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public List<StatementEventResponse> getEvents() {
        return events;
    }

    public void setEvents(List<StatementEventResponse> events) {
        this.events = events != null ? events : Collections.emptyList();
        this.totalEvents = this.events.size();
    }

    public int getTotalEvents() {
        return totalEvents;
    }

    public void setTotalEvents(int totalEvents) {
        this.totalEvents = totalEvents;
    }

    public Instant getAsOf() {
        return asOf;
    }

    public void setAsOf(Instant asOf) {
        this.asOf = asOf;
    }
}
