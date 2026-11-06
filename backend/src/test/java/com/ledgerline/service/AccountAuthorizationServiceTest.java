package com.ledgerline.service;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.AccountStatus;
import com.ledgerline.entity.AccountType;
import com.ledgerline.entity.PermissionType;
import com.ledgerline.entity.User;
import com.ledgerline.entity.UserRole;
import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.repository.AccountPermissionRepository;
import com.ledgerline.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Account Authorization Service Unit Tests")
class AccountAuthorizationServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountPermissionRepository accountPermissionRepository;

    @Mock
    private AuthService authService;

    @InjectMocks
    private AccountAuthorizationService accountAuthorizationService;

    private User aliceHolder;
    private User bobHolder;
    private User auditorUser;
    private User adminUser;
    private Account aliceAccount;
    private Account frozenAccount;
    private Account closedAccount;

    @BeforeEach
    void setUp() {
        aliceHolder = new User("alice", "alice@example.com", "hash1", UserRole.ACCOUNT_HOLDER);
        aliceHolder.setId(101L);

        bobHolder = new User("bob", "bob@example.com", "hash2", UserRole.ACCOUNT_HOLDER);
        bobHolder.setId(102L);

        auditorUser = new User("auditor", "auditor@example.com", "hash3", UserRole.AUDITOR);
        auditorUser.setId(103L);

        adminUser = new User("admin", "admin@example.com", "hash4", UserRole.ADMINISTRATOR);
        adminUser.setId(104L);

        aliceAccount = new Account("ACC-101", "Alice Checking", AccountType.CUSTOMER, "INR");
        aliceAccount.setId(201L);
        aliceAccount.setStatus(AccountStatus.ACTIVE);

        frozenAccount = new Account("ACC-102", "Frozen Account", AccountType.CUSTOMER, "INR");
        frozenAccount.setId(202L);
        frozenAccount.setStatus(AccountStatus.FROZEN);

        closedAccount = new Account("ACC-103", "Closed Account", AccountType.CUSTOMER, "INR");
        closedAccount.setId(203L);
        closedAccount.setStatus(AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("hasPermission: returns false when any parameter is null")
    void testHasPermission_NullParameters() {
        assertFalse(accountAuthorizationService.hasPermission(null, 201L, PermissionType.CAN_VIEW_STATEMENT));
        assertFalse(accountAuthorizationService.hasPermission(aliceHolder, null, PermissionType.CAN_VIEW_STATEMENT));
        assertFalse(accountAuthorizationService.hasPermission(aliceHolder, 201L, null));
    }

    @Test
    @DisplayName("hasPermission: returns false when account does not exist")
    void testHasPermission_AccountNotFound() {
        when(accountRepository.findById(999L)).thenReturn(Optional.empty());

        assertFalse(accountAuthorizationService.hasPermission(aliceHolder, 999L, PermissionType.CAN_VIEW_STATEMENT));
        verify(accountRepository).findById(999L);
        verifyNoInteractions(accountPermissionRepository);
    }

    @Test
    @DisplayName("hasPermission: returns true when account holder has explicit permission")
    void testHasPermission_AccountHolder_Granted() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));
        when(accountPermissionRepository.existsByUserIdAndAccountIdAndPermission(101L, 201L, PermissionType.CAN_TRANSFER))
                .thenReturn(true);

        boolean granted = accountAuthorizationService.hasPermission(aliceHolder, 201L, PermissionType.CAN_TRANSFER);

        assertTrue(granted);
        verify(accountPermissionRepository).existsByUserIdAndAccountIdAndPermission(101L, 201L, PermissionType.CAN_TRANSFER);
    }

    @Test
    @DisplayName("hasPermission: returns false when account holder lacks explicit permission")
    void testHasPermission_AccountHolder_Denied() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));
        when(accountPermissionRepository.existsByUserIdAndAccountIdAndPermission(101L, 201L, PermissionType.CAN_WITHDRAW))
                .thenReturn(false);

        boolean granted = accountAuthorizationService.hasPermission(aliceHolder, 201L, PermissionType.CAN_WITHDRAW);

        assertFalse(granted);
    }

    @Test
    @DisplayName("hasPermission: AUDITOR role is granted CAN_VIEW_STATEMENT read-only permission")
    void testHasPermission_Auditor_ReadOnlyAllowed() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));

        boolean canViewStatement = accountAuthorizationService.hasPermission(auditorUser, 201L, PermissionType.CAN_VIEW_STATEMENT);

        assertTrue(canViewStatement);
        verifyNoInteractions(accountPermissionRepository);
    }

    @Test
    @DisplayName("hasPermission: AUDITOR role is denied mutating permissions (CAN_DEPOSIT, CAN_WITHDRAW, CAN_TRANSFER, CAN_ADJUST)")
    void testHasPermission_Auditor_MutatingDenied() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));

        assertFalse(accountAuthorizationService.hasPermission(auditorUser, 201L, PermissionType.CAN_DEPOSIT));
        assertFalse(accountAuthorizationService.hasPermission(auditorUser, 201L, PermissionType.CAN_WITHDRAW));
        assertFalse(accountAuthorizationService.hasPermission(auditorUser, 201L, PermissionType.CAN_TRANSFER));
        assertFalse(accountAuthorizationService.hasPermission(auditorUser, 201L, PermissionType.CAN_ADJUST));

        verifyNoInteractions(accountPermissionRepository);
    }

    @Test
    @DisplayName("hasPermission: ADMINISTRATOR role has CAN_VIEW_STATEMENT and CAN_ADJUST, but denied customer mutations")
    void testHasPermission_Administrator_Rules() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));

        assertTrue(accountAuthorizationService.hasPermission(adminUser, 201L, PermissionType.CAN_VIEW_STATEMENT));
        assertTrue(accountAuthorizationService.hasPermission(adminUser, 201L, PermissionType.CAN_ADJUST));
        assertFalse(accountAuthorizationService.hasPermission(adminUser, 201L, PermissionType.CAN_DEPOSIT));
        assertFalse(accountAuthorizationService.hasPermission(adminUser, 201L, PermissionType.CAN_WITHDRAW));
        assertFalse(accountAuthorizationService.hasPermission(adminUser, 201L, PermissionType.CAN_TRANSFER));

        verifyNoInteractions(accountPermissionRepository);
    }

    @Test
    @DisplayName("requirePermission: succeeds without exception when permission exists")
    void testRequirePermission_Success() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));
        when(accountPermissionRepository.existsByUserIdAndAccountIdAndPermission(101L, 201L, PermissionType.CAN_TRANSFER))
                .thenReturn(true);

        assertDoesNotThrow(() ->
                accountAuthorizationService.requirePermission(aliceHolder, 201L, PermissionType.CAN_TRANSFER));
    }

    @Test
    @DisplayName("requirePermission: throws AccountAccessDeniedException on non-existent account without leaking existence")
    void testRequirePermission_NonExistentAccount_DoesNotLeak() {
        when(accountRepository.findById(999L)).thenReturn(Optional.empty());

        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountAuthorizationService.requirePermission(aliceHolder, 999L, PermissionType.CAN_VIEW_STATEMENT));

        assertTrue(ex.getMessage().contains("Access denied to account 999"));
    }

    @Test
    @DisplayName("requirePermission: throws AccountAccessDeniedException when account holder lacks permission")
    void testRequirePermission_MissingPermission_ThrowsAccessDenied() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));
        when(accountPermissionRepository.existsByUserIdAndAccountIdAndPermission(101L, 201L, PermissionType.CAN_TRANSFER))
                .thenReturn(false);

        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountAuthorizationService.requirePermission(aliceHolder, 201L, PermissionType.CAN_TRANSFER));

        assertTrue(ex.getMessage().contains("Access denied"));
        assertTrue(ex.getMessage().contains("lacks CAN_TRANSFER permission"));
    }

    @Test
    @DisplayName("requirePermission: throws AccountAccessDeniedException when Bob attempts to access Alice's account")
    void testRequirePermission_CrossUserAccess_ThrowsAccessDenied() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));
        when(accountPermissionRepository.existsByUserIdAndAccountIdAndPermission(102L, 201L, PermissionType.CAN_VIEW_STATEMENT))
                .thenReturn(false);

        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountAuthorizationService.requirePermission(bobHolder, 201L, PermissionType.CAN_VIEW_STATEMENT));

        assertTrue(ex.getMessage().contains("bob"));
        assertTrue(ex.getMessage().contains("lacks CAN_VIEW_STATEMENT"));
    }

    @Test
    @DisplayName("requirePermission: throws AccountAccessDeniedException when Auditor attempts mutating operations")
    void testRequirePermission_AuditorMutating_ThrowsAccessDenied() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));

        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountAuthorizationService.requirePermission(auditorUser, 201L, PermissionType.CAN_TRANSFER));

        assertTrue(ex.getMessage().contains("Auditor role has strictly read-only access"));
    }

    @Test
    @DisplayName("requirePermission: throws AccountAccessDeniedException when Administrator attempts customer deposits/withdrawals")
    void testRequirePermission_AdministratorMutating_ThrowsAccessDenied() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));

        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountAuthorizationService.requirePermission(adminUser, 201L, PermissionType.CAN_DEPOSIT));

        assertTrue(ex.getMessage().contains("Administrator role cannot perform direct customer financial operations"));
    }

    @Test
    @DisplayName("requirePermission: delegates to authService.getAuthenticatedUser() when called with accountId and permission")
    void testRequirePermission_WithContextUser() {
        when(authService.getAuthenticatedUser()).thenReturn(aliceHolder);
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));
        when(accountPermissionRepository.existsByUserIdAndAccountIdAndPermission(101L, 201L, PermissionType.CAN_VIEW_STATEMENT))
                .thenReturn(true);

        assertDoesNotThrow(() ->
                accountAuthorizationService.requirePermission(201L, PermissionType.CAN_VIEW_STATEMENT));

        verify(authService).getAuthenticatedUser();
    }

    @Test
    @DisplayName("requireActiveAccount: succeeds for ACTIVE account")
    void testRequireActiveAccount_Active_Success() {
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));

        Account result = accountAuthorizationService.requireActiveAccount(201L);
        assertNotNull(result);
        assertEquals(AccountStatus.ACTIVE, result.getStatus());
    }

    @Test
    @DisplayName("requireActiveAccount: rejects FROZEN account with AccountAccessDeniedException")
    void testRequireActiveAccount_Frozen_ThrowsAccessDenied() {
        when(accountRepository.findById(202L)).thenReturn(Optional.of(frozenAccount));

        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountAuthorizationService.requireActiveAccount(202L));

        assertTrue(ex.getMessage().contains("FROZEN and cannot perform financial operations"));
    }

    @Test
    @DisplayName("requireActiveAccount: rejects CLOSED account with AccountAccessDeniedException")
    void testRequireActiveAccount_Closed_ThrowsAccessDenied() {
        when(accountRepository.findById(203L)).thenReturn(Optional.of(closedAccount));

        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountAuthorizationService.requireActiveAccount(203L));

        assertTrue(ex.getMessage().contains("CLOSED and cannot perform financial operations"));
    }

    @Test
    @DisplayName("requireActiveAccount: rejects non-existent account without leaking existence")
    void testRequireActiveAccount_NonExistent_ThrowsAccessDenied() {
        when(accountRepository.findById(999L)).thenReturn(Optional.empty());

        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountAuthorizationService.requireActiveAccount(999L));

        assertTrue(ex.getMessage().contains("Access denied to account 999"));
    }
}
