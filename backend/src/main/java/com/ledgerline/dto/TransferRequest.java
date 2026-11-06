package com.ledgerline.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload for transferring funds between two customer accounts.
 * Client identifies source and destination accounts, amount, and optional description.
 * Source and destination accounts must be distinct.
 * Initiating user, transaction status, ledger entries, and balances
 * are managed exclusively by the server and cannot be supplied by the client.
 */
public class TransferRequest {

    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");
    public static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");

    @NotNull(message = "Source account ID is required")
    @Positive(message = "Source account ID must be positive")
    private Long sourceAccountId;

    @NotNull(message = "Destination account ID is required")
    @Positive(message = "Destination account ID must be positive")
    private Long destinationAccountId;

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

    public TransferRequest() {}

    public TransferRequest(Long sourceAccountId, Long destinationAccountId, BigDecimal amount) {
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.currency = "INR";
    }

    public TransferRequest(Long sourceAccountId, Long destinationAccountId, BigDecimal amount, String currency) {
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.currency = currency;
    }

    public TransferRequest(Long sourceAccountId, Long destinationAccountId, BigDecimal amount, String currency, String description) {
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
    }

    @AssertTrue(message = "Source and destination accounts must be different")
    public boolean isSourceAndDestinationDifferent() {
        if (sourceAccountId == null || destinationAccountId == null) {
            return true; // handled by @NotNull
        }
        return !sourceAccountId.equals(destinationAccountId);
    }

    /**
     * Programmatic validation enforcing all domain constraints.
     * Throws IllegalArgumentException on violation.
     */
    public void validate() {
        if (sourceAccountId == null) {
            throw new IllegalArgumentException("Source account ID is required");
        }
        if (sourceAccountId <= 0) {
            throw new IllegalArgumentException("Source account ID must be positive");
        }
        if (destinationAccountId == null) {
            throw new IllegalArgumentException("Destination account ID is required");
        }
        if (destinationAccountId <= 0) {
            throw new IllegalArgumentException("Destination account ID must be positive");
        }
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new IllegalArgumentException("Source and destination accounts must be different");
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

    public Long getSourceAccountId() {
        return sourceAccountId;
    }

    public void setSourceAccountId(Long sourceAccountId) {
        this.sourceAccountId = sourceAccountId;
    }

    public Long getDestinationAccountId() {
        return destinationAccountId;
    }

    public void setDestinationAccountId(Long destinationAccountId) {
        this.destinationAccountId = destinationAccountId;
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
