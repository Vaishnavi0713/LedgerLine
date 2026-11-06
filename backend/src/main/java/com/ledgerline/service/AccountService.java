package com.ledgerline.service;

import com.ledgerline.dto.AccountBalanceResponse;
import com.ledgerline.dto.AccountResponse;
import com.ledgerline.dto.AdminCreateAccountRequest;
import com.ledgerline.dto.CreateAccountRequest;
import com.ledgerline.entity.*;
import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.exception.ResourceNotFoundException;
import com.ledgerline.repository.AccountPermissionRepository;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Year;
import java.util.List;

@Service
public class AccountService {

    private static final String DEFAULT_CURRENCY = "INR";
    private static final BigDecimal INITIAL_ZERO_BALANCE = BigDecimal.ZERO.setScale(2);

    private final AccountRepository accountRepository;
    private final AccountPermissionRepository accountPermissionRepository;
    private final UserRepository userRepository;
    private final AccountAuthorizationService accountAuthorizationService;
    private final AuthService authService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AccountService(
            AccountRepository accountRepository,
            AccountPermissionRepository accountPermissionRepository,
            UserRepository userRepository,
            AccountAuthorizationService accountAuthorizationService,
            AuthService authService) {
        this.accountRepository = accountRepository;
        this.accountPermissionRepository = accountPermissionRepository;
        this.userRepository = userRepository;
        this.accountAuthorizationService = accountAuthorizationService;
        this.authService = authService;
    }

    /**
     * Creates a new account for the authenticated user.
     * The owner identity is strictly derived from the authenticated JWT principal.
     * The account always starts with a zero balance (0.00 INR).
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountResponse createAccount(CreateAccountRequest request) {
        User currentUser = authService.getAuthenticatedUser();

        // Security check: Auditors have strictly read-only access and cannot create accounts
        if (currentUser.getRole() == UserRole.AUDITOR) {
            throw new AccountAccessDeniedException("Auditor role has strictly read-only access and cannot create accounts.");
        }

        validateCurrency(request.getCurrency());
        String name = validateAndSanitizeName(request.getName());

        String accountNumber = generateUniqueAccountNumber();

        // 1. Persist account with strictly zero balance and ACTIVE status
        Account account = new Account(accountNumber, name, AccountType.CUSTOMER, DEFAULT_CURRENCY);
        account.setBalance(INITIAL_ZERO_BALANCE);
        account.setStatus(AccountStatus.ACTIVE);
        account.setStatementSequenceCounter(0L);

        Account savedAccount = accountRepository.save(account);

        // 2. Atomically assign customer permissions to the authenticated owner
        assignDefaultCustomerPermissions(currentUser, savedAccount);

        return AccountResponse.fromEntity(savedAccount);
    }

    /**
     * Administrative account creation for an existing user.
     * Restricted to ADMINISTRATOR role.
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountResponse createAccountByAdmin(AdminCreateAccountRequest request) {
        User currentUser = authService.getAuthenticatedUser();
        if (currentUser.getRole() != UserRole.ADMINISTRATOR) {
            throw new AccountAccessDeniedException("Administrator role required to create accounts for other users.");
        }

        if (request.getUserId() == null) {
            throw new IllegalArgumentException("Target user ID cannot be null.");
        }

        User targetUser = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Target user not found with ID: " + request.getUserId()));

        if (!targetUser.isActive()) {
            throw new IllegalArgumentException("Cannot create an account for a deactivated user.");
        }

        validateCurrency(request.getCurrency());
        String name = validateAndSanitizeName(request.getName());
        AccountType accountType = request.getAccountType() != null ? request.getAccountType() : AccountType.CUSTOMER;

        String accountNumber = generateUniqueAccountNumber();

        Account account = new Account(accountNumber, name, accountType, DEFAULT_CURRENCY);
        account.setBalance(INITIAL_ZERO_BALANCE);
        account.setStatus(AccountStatus.ACTIVE);
        account.setStatementSequenceCounter(0L);

        Account savedAccount = accountRepository.save(account);

        // Atomically assign customer permissions to the target user
        assignDefaultCustomerPermissions(targetUser, savedAccount);

        return AccountResponse.fromEntity(savedAccount);
    }

    /**
     * Retrieves all accounts accessible to the current authenticated user.
     * - ACCOUNT_HOLDER: returns only accounts authorized via account_permissions.
     * - ADMINISTRATOR / AUDITOR: returns all accounts across the system.
     */
    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsForCurrentUser() {
        User currentUser = authService.getAuthenticatedUser();

        if (currentUser.getRole() == UserRole.ADMINISTRATOR || currentUser.getRole() == UserRole.AUDITOR) {
            return accountRepository.findAll().stream()
                    .map(AccountResponse::fromEntity)
                    .toList();
        }

        return accountRepository.findAccountsByUserId(currentUser.getId()).stream()
                .map(AccountResponse::fromEntity)
                .toList();
    }

    /**
     * Retrieves account details by ID after validating read authorization.
     */
    @Transactional(readOnly = true)
    public AccountResponse getAccountById(Long accountId) {
        User currentUser = authService.getAuthenticatedUser();
        accountAuthorizationService.requirePermission(currentUser, accountId, PermissionType.CAN_VIEW_STATEMENT);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountAccessDeniedException("Access denied to account " + accountId));

        return AccountResponse.fromEntity(account);
    }

    /**
     * Retrieves account balance by ID after validating read authorization.
     */
    @Transactional(readOnly = true)
    public AccountBalanceResponse getAccountBalance(Long accountId) {
        User currentUser = authService.getAuthenticatedUser();
        accountAuthorizationService.requirePermission(currentUser, accountId, PermissionType.CAN_VIEW_STATEMENT);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountAccessDeniedException("Access denied to account " + accountId));

        return AccountBalanceResponse.fromEntity(account);
    }

    /**
     * Retrieves all accounts across all users for administrative inspection.
     */
    @Transactional(readOnly = true)
    public List<AccountResponse> getAllAccountsForAdmin() {
        User currentUser = authService.getAuthenticatedUser();
        if (currentUser.getRole() != UserRole.ADMINISTRATOR) {
            throw new AccountAccessDeniedException("Administrator role required to access administrative account list.");
        }

        return accountRepository.findAll().stream()
                .map(AccountResponse::fromEntity)
                .toList();
    }

    /**
     * Administrative status update (e.g. ACTIVE -> FROZEN, ACTIVE -> CLOSED).
     * Modifies only the status without altering balances or historical ledger records.
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountResponse updateAccountStatus(Long accountId, AccountStatus newStatus) {
        User currentUser = authService.getAuthenticatedUser();
        if (currentUser.getRole() != UserRole.ADMINISTRATOR) {
            throw new AccountAccessDeniedException("Administrator role required to update account status.");
        }

        if (newStatus == null) {
            throw new IllegalArgumentException("Account status cannot be null.");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found with ID: " + accountId));

        account.setStatus(newStatus);
        account.setUpdatedAt(Instant.now());

        Account updatedAccount = accountRepository.save(account);
        return AccountResponse.fromEntity(updatedAccount);
    }

    /**
     * Assigns the 4 standard customer permissions to the account owner.
     */
    private void assignDefaultCustomerPermissions(User user, Account account) {
        List<PermissionType> permissions = List.of(
                PermissionType.CAN_DEPOSIT,
                PermissionType.CAN_WITHDRAW,
                PermissionType.CAN_TRANSFER,
                PermissionType.CAN_VIEW_STATEMENT
        );

        for (PermissionType permission : permissions) {
            AccountPermission perm = new AccountPermission(user, account, permission);
            accountPermissionRepository.save(perm);
        }
    }

    /**
     * Securely generates a unique account number: ACC-YYYY-XXXXXXXXXX.
     */
    private String generateUniqueAccountNumber() {
        int currentYear = Year.now().getValue();
        for (int attempt = 0; attempt < 10; attempt++) {
            byte[] randomBytes = new byte[5];
            secureRandom.nextBytes(randomBytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : randomBytes) {
                hex.append(String.format("%02X", b));
            }
            String candidate = "ACC-" + currentYear + "-" + hex;
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Failed to generate a unique account number after multiple attempts.");
    }

    private void validateCurrency(String currency) {
        if (currency != null && !currency.isBlank() && !DEFAULT_CURRENCY.equalsIgnoreCase(currency.trim())) {
            throw new IllegalArgumentException("Currency must be INR. Unsupported currency: " + currency);
        }
    }

    private String validateAndSanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Account name cannot be blank.");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("Account name cannot exceed 100 characters.");
        }
        return trimmed;
    }
}
