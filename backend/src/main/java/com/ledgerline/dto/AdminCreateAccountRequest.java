package com.ledgerline.dto;

import com.ledgerline.entity.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AdminCreateAccountRequest {

    @NotNull(message = "Target user ID is required")
    private Long userId;

    @NotBlank(message = "Account name is required")
    @Size(min = 1, max = 100, message = "Account name must be between 1 and 100 characters")
    private String name;

    private AccountType accountType = AccountType.CUSTOMER;

    @Pattern(regexp = "^INR$", message = "Currency must be INR")
    private String currency = "INR";

    public AdminCreateAccountRequest() {}

    public AdminCreateAccountRequest(Long userId, String name) {
        this.userId = userId;
        this.name = name;
        this.accountType = AccountType.CUSTOMER;
        this.currency = "INR";
    }

    public AdminCreateAccountRequest(Long userId, String name, AccountType accountType, String currency) {
        this.userId = userId;
        this.name = name;
        this.accountType = (accountType != null) ? accountType : AccountType.CUSTOMER;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "INR";
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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
}
