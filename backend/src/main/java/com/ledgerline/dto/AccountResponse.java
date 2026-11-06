package com.ledgerline.dto;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.AccountStatus;
import com.ledgerline.entity.AccountType;

import java.math.BigDecimal;
import java.time.Instant;

public class AccountResponse {

    private Long id;
    private String accountNumber;
    private String name;
    private AccountType accountType;
    private String currency;
    private BigDecimal balance;
    private Long statementSequenceCounter;
    private AccountStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public AccountResponse() {}

    public AccountResponse(Long id, String accountNumber, String name, AccountType accountType,
                           String currency, BigDecimal balance, Long statementSequenceCounter,
                           AccountStatus status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.name = name;
        this.accountType = accountType;
        this.currency = currency;
        this.balance = balance;
        this.statementSequenceCounter = statementSequenceCounter;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static AccountResponse fromEntity(Account account) {
        if (account == null) {
            return null;
        }
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getName(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatementSequenceCounter(),
                account.getStatus(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getStatementSequenceCounter() {
        return statementSequenceCounter;
    }

    public void setStatementSequenceCounter(Long statementSequenceCounter) {
        this.statementSequenceCounter = statementSequenceCounter;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public void setStatus(AccountStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
