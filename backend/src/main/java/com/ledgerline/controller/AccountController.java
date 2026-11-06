package com.ledgerline.controller;

import com.ledgerline.dto.AccountBalanceResponse;
import com.ledgerline.dto.AccountResponse;
import com.ledgerline.dto.CreateAccountRequest;
import com.ledgerline.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Creates an account for the authenticated account holder.
     * Starts with zero balance (0.00 INR).
     * Server extracts owner identity from JWT principal.
     */
    @PostMapping
    @PreAuthorize("hasRole('ACCOUNT_HOLDER')")
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists all accounts accessible to the current authenticated user.
     */
    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAccounts() {
        List<AccountResponse> accounts = accountService.getAccountsForCurrentUser();
        return ResponseEntity.ok(accounts);
    }

    /**
     * Retrieves account details by ID.
     * Enforces fine-grained account authorization.
     */
    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccountById(@PathVariable Long accountId) {
        AccountResponse response = accountService.getAccountById(accountId);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves account balance by ID.
     * Enforces fine-grained account authorization.
     */
    @GetMapping("/{accountId}/balance")
    public ResponseEntity<AccountBalanceResponse> getAccountBalance(@PathVariable Long accountId) {
        AccountBalanceResponse response = accountService.getAccountBalance(accountId);
        return ResponseEntity.ok(response);
    }
}
