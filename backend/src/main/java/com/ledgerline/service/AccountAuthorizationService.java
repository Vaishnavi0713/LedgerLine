package com.ledgerline.service;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.PermissionType;
import com.ledgerline.entity.User;
import com.ledgerline.entity.UserRole;
import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.repository.AccountPermissionRepository;
import com.ledgerline.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountAuthorizationService {

    private final AccountRepository accountRepository;
    private final AccountPermissionRepository accountPermissionRepository;
    private final AuthService authService;

    public AccountAuthorizationService(
            AccountRepository accountRepository,
            AccountPermissionRepository accountPermissionRepository,
            AuthService authService) {
        this.accountRepository = accountRepository;
        this.accountPermissionRepository = accountPermissionRepository;
        this.authService = authService;
    }

    /**
     * Checks if a user has a specific permission on an account.
     * Denies access by default.
     */
    @Transactional(readOnly = true)
    public boolean hasPermission(User user, Long accountId, PermissionType permission) {
        if (user == null || accountId == null || permission == null) {
            return false;
        }

        // Account must exist; throw exception without leaking whether it exists
        Account account = accountRepository.findById(accountId)
                .orElse(null);
        if (account == null) {
            return false;
        }

        // AUDITOR role rules:
        // Auditors have strictly read-only access (CAN_VIEW_STATEMENT)
        // Auditors NEVER have mutating permissions (CAN_DEPOSIT, CAN_WITHDRAW, CAN_TRANSFER, CAN_ADJUST)
        if (user.getRole() == UserRole.AUDITOR) {
            return permission == PermissionType.CAN_VIEW_STATEMENT;
        }

        // Check account_permissions table
        return accountPermissionRepository.existsByUserIdAndAccountIdAndPermission(
                user.getId(), accountId, permission);
    }

    /**
     * Enforces that the specified user has the required permission on the account.
     * Throws AccountAccessDeniedException if unauthorized.
     */
    @Transactional(readOnly = true)
    public void requirePermission(User user, Long accountId, PermissionType permission) {
        if (user == null || accountId == null || permission == null) {
            throw new AccountAccessDeniedException("Authorization denied: missing security parameters.");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountAccessDeniedException("Access denied to account " + accountId));

        if (user.getRole() == UserRole.AUDITOR) {
            if (permission != PermissionType.CAN_VIEW_STATEMENT) {
                throw new AccountAccessDeniedException("Auditor role has strictly read-only access and cannot perform mutating financial operations.");
            }
            return;
        }

        boolean authorized = accountPermissionRepository.existsByUserIdAndAccountIdAndPermission(
                user.getId(), accountId, permission);

        if (!authorized) {
            throw new AccountAccessDeniedException("Access denied: user '" + user.getUsername() +
                    "' lacks " + permission.name() + " permission on account " + accountId);
        }
    }

    /**
     * Enforces that the current authenticated user in SecurityContext has the required permission.
     */
    @Transactional(readOnly = true)
    public void requirePermission(Long accountId, PermissionType permission) {
        User currentUser = authService.getAuthenticatedUser();
        requirePermission(currentUser, accountId, permission);
    }
}
