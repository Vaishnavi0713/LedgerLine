package com.ledgerline.dto;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;

public class AccountBalanceResponse {

    private Long accountId;
    private String accountNumber;
    private String currency;
    private BigDecimal balance;
    private AccountStatus status;
    private Instant asOf;

    public AccountBalanceResponse() {}

    public AccountBalanceResponse(Long accountId, String accountNumber, String currency,
                                  BigDecimal balance, AccountStatus status, Instant asOf) {
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.balance = balance;
        this.status = status;
        this.asOf = asOf;
    }

    public static AccountBalanceResponse fromEntity(Account account) {
        if (account == null) {
            return null;
        }
        return new AccountBalanceResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus(),
                Instant.now()
        );
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

    public Instant getAsOf() {
        return asOf;
    }

    public void setAsOf(Instant asOf) {
        this.asOf = asOf;
    }
}
