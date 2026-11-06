package com.ledgerline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateAccountRequest {

    @NotBlank(message = "Account name is required")
    @Size(min = 1, max = 100, message = "Account name must be between 1 and 100 characters")
    private String name;

    @Pattern(regexp = "^INR$", message = "Currency must be INR")
    private String currency = "INR";

    public CreateAccountRequest() {}

    public CreateAccountRequest(String name) {
        this.name = name;
        this.currency = "INR";
    }

    public CreateAccountRequest(String name, String currency) {
        this.name = name;
        this.currency = (currency != null && !currency.isBlank()) ? currency : "INR";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
