package com.ledgerline.controller;

import com.ledgerline.dto.AccountResponse;
import com.ledgerline.dto.AdminCreateAccountRequest;
import com.ledgerline.dto.AdminCreateUserRequest;
import com.ledgerline.dto.UpdateAccountStatusRequest;
import com.ledgerline.dto.UserResponse;
import com.ledgerline.entity.User;
import com.ledgerline.exception.DuplicateResourceException;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountService accountService;

    public AdminController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AccountService accountService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accountService = accountService;
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> listUsers() {
        List<UserResponse> users = userRepository.findAll().stream()
                .map(UserResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(users);
    }

    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody AdminCreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("User with email '" + request.getEmail() + "' already exists.");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("User with username '" + request.getUsername() + "' already exists.");
        }

        User user = new User(
                request.getUsername(),
                request.getEmail(),
                passwordEncoder.encode(request.getPassword()),
                request.getRole()
        );
        User savedUser = userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.fromEntity(savedUser));
    }

    @GetMapping("/system-info")
    public ResponseEntity<Map<String, Object>> getSystemInfo() {
        return ResponseEntity.ok(Map.of(
                "application", "LedgerLine",
                "status", "HEALTHY",
                "totalUsers", userRepository.count(),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Lists all accounts across all users for administrative review.
     */
    @GetMapping("/accounts")
    public ResponseEntity<List<AccountResponse>> listAllAccounts() {
        List<AccountResponse> accounts = accountService.getAllAccountsForAdmin();
        return ResponseEntity.ok(accounts);
    }

    /**
     * Creates an account for a specified user as an administrator.
     */
    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> createAccountForUser(@Valid @RequestBody AdminCreateAccountRequest request) {
        AccountResponse response = accountService.createAccountByAdmin(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Updates an account's status (ACTIVE, FROZEN, CLOSED).
     */
    @PatchMapping("/accounts/{accountId}/status")
    public ResponseEntity<AccountResponse> updateAccountStatus(
            @PathVariable Long accountId,
            @Valid @RequestBody UpdateAccountStatusRequest request) {
        AccountResponse response = accountService.updateAccountStatus(accountId, request.getStatus());
        return ResponseEntity.ok(response);
    }
}
