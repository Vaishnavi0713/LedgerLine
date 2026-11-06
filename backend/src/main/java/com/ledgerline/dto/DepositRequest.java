package com.ledgerline.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload for depositing funds into a customer account.
 * Client identifies destination account, amount, and optional description.
 * System clearing account (ID 1), initiating user, and transaction status
 * are managed exclusively by the server and cannot be supplied by the client.
 */
public class DepositRequest {

    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");
    public static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");

    @NotNull(message = "Account ID is required")
    @Positive(message = "Account ID must be positive")
    private Long accountId;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be strictly positive (minimum 0.01 INR)")
    @DecimalMax(value = "99999999999999999.99", message = "Amount exceeds maximum supported DECIMAL(19,2) value")
    @Digits(integer = 17, fraction = 2, message = "Amount must have at most 17 integer digits and 2 decimal places")
    private BigDecimal amount;

    @NotNull(message = "Currency is required")
    @Pattern(regexp = "^INR$", message = "Currency must be INR")
    private String currency = "INR";

    @Size(max = 255, message = "Description must not exceed 255 characters")
    private String description;

    public DepositRequest() {}

    public DepositRequest(Long accountId, BigDecimal amount) {
        this.accountId = accountId;
        this.amount = amount;
        this.currency = "INR";
    }

    public DepositRequest(Long accountId, BigDecimal amount, String currency) {
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
    }

    public DepositRequest(Long accountId, BigDecimal amount, String currency, String description) {
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
    }

    /**
     * Programmatic validation enforcing all domain constraints.
     * Throws IllegalArgumentException on violation.
     */
    public void validate() {
        if (accountId == null) {
            throw new IllegalArgumentException("Account ID is required");
        }
        if (accountId <= 0) {
            throw new IllegalArgumentException("Account ID must be positive");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be strictly positive");
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("Amount must have at most 2 decimal places");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Amount exceeds maximum supported DECIMAL(19,2) value (99999999999999999.99)");
        }
        if (currency == null || !currency.equals("INR")) {
            throw new IllegalArgumentException("Currency must be INR");
        }
        if (description != null && description.length() > 255) {
            throw new IllegalArgumentException("Description must not exceed 255 characters");
        }
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
