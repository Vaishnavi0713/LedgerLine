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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Account Service Unit Tests")
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountPermissionRepository accountPermissionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountAuthorizationService accountAuthorizationService;

    @Mock
    private AuthService authService;

    @InjectMocks
    private AccountService accountService;

    private User aliceHolder;
    private User bobHolder;
    private User auditorUser;
    private User adminUser;
    private Account aliceAccount;

    @BeforeEach
    void setUp() {
        aliceHolder = new User("alice", "alice@ledgerline.test", "hash1", UserRole.ACCOUNT_HOLDER);
        aliceHolder.setId(101L);

        bobHolder = new User("bob", "bob@ledgerline.test", "hash2", UserRole.ACCOUNT_HOLDER);
        bobHolder.setId(102L);

        auditorUser = new User("auditor", "auditor@ledgerline.test", "hash3", UserRole.AUDITOR);
        auditorUser.setId(103L);

        adminUser = new User("admin", "admin@ledgerline.test", "hash4", UserRole.ADMINISTRATOR);
        adminUser.setId(104L);

        aliceAccount = new Account("ACC-2026-A1B2C3D4E5", "Primary Checking", AccountType.CUSTOMER, "INR");
        aliceAccount.setId(201L);
        aliceAccount.setBalance(BigDecimal.ZERO.setScale(2));
        aliceAccount.setStatus(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("createAccount: Account Holder successfully creates account with zero balance and 4 permissions")
    void testCreateAccount_Success() {
        when(authService.getAuthenticatedUser()).thenReturn(aliceHolder);
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account acc = invocation.getArgument(0);
            acc.setId(201L);
            return acc;
        });

        CreateAccountRequest request = new CreateAccountRequest("My Savings", "INR");
        AccountResponse response = accountService.createAccount(request);

        assertNotNull(response);
        assertEquals(201L, response.getId());
        assertEquals("My Savings", response.getName());
        assertEquals(AccountType.CUSTOMER, response.getAccountType());
        assertEquals("INR", response.getCurrency());
        assertEquals(new BigDecimal("0.00"), response.getBalance());
        assertEquals(AccountStatus.ACTIVE, response.getStatus());

        // Verify Account saved with 0.00 balance
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account capturedAccount = accountCaptor.getValue();
        assertEquals(new BigDecimal("0.00"), capturedAccount.getBalance());
        assertEquals(0L, capturedAccount.getStatementSequenceCounter());
        assertTrue(capturedAccount.getAccountNumber().startsWith("ACC-"));

        // Verify 4 permissions atomically assigned
        ArgumentCaptor<AccountPermission> permCaptor = ArgumentCaptor.forClass(AccountPermission.class);
        verify(accountPermissionRepository, times(4)).save(permCaptor.capture());
        List<AccountPermission> savedPerms = permCaptor.getAllValues();
        assertEquals(4, savedPerms.size());
        assertTrue(savedPerms.stream().allMatch(p -> p.getUser().getId().equals(101L)));
        assertTrue(savedPerms.stream().anyMatch(p -> p.getPermission() == PermissionType.CAN_DEPOSIT));
        assertTrue(savedPerms.stream().anyMatch(p -> p.getPermission() == PermissionType.CAN_WITHDRAW));
        assertTrue(savedPerms.stream().anyMatch(p -> p.getPermission() == PermissionType.CAN_TRANSFER));
        assertTrue(savedPerms.stream().anyMatch(p -> p.getPermission() == PermissionType.CAN_VIEW_STATEMENT));
    }

    @Test
    @DisplayName("createAccount: Auditor is forbidden from creating accounts")
    void testCreateAccount_AuditorForbidden() {
        when(authService.getAuthenticatedUser()).thenReturn(auditorUser);

        CreateAccountRequest request = new CreateAccountRequest("Auditor Account");
        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountService.createAccount(request));

        assertTrue(ex.getMessage().contains("Auditor role has strictly read-only access"));
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(accountPermissionRepository);
    }

    @Test
    @DisplayName("createAccount: Rejects unsupported currency")
    void testCreateAccount_InvalidCurrency() {
        when(authService.getAuthenticatedUser()).thenReturn(aliceHolder);

        CreateAccountRequest request = new CreateAccountRequest("USD Account", "USD");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                accountService.createAccount(request));

        assertTrue(ex.getMessage().contains("Currency must be INR"));
        verifyNoInteractions(accountRepository);
    }

    @Test
    @DisplayName("createAccount: Rejects blank account name")
    void testCreateAccount_BlankName() {
        when(authService.getAuthenticatedUser()).thenReturn(aliceHolder);

        CreateAccountRequest request = new CreateAccountRequest("   ", "INR");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                accountService.createAccount(request));

        assertTrue(ex.getMessage().contains("Account name cannot be blank"));
        verifyNoInteractions(accountRepository);
    }

    @Test
    @DisplayName("createAccountByAdmin: Administrator creates account for existing target user")
    void testCreateAccountByAdmin_Success() {
        when(authService.getAuthenticatedUser()).thenReturn(adminUser);
        when(userRepository.findById(101L)).thenReturn(Optional.of(aliceHolder));
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account acc = invocation.getArgument(0);
            acc.setId(301L);
            return acc;
        });

        AdminCreateAccountRequest request = new AdminCreateAccountRequest(101L, "Admin Assigned Account");
        AccountResponse response = accountService.createAccountByAdmin(request);

        assertNotNull(response);
        assertEquals(301L, response.getId());
        assertEquals("Admin Assigned Account", response.getName());
        assertEquals(new BigDecimal("0.00"), response.getBalance());

        // Verify permissions assigned to aliceHolder (target user), NOT adminUser
        ArgumentCaptor<AccountPermission> permCaptor = ArgumentCaptor.forClass(AccountPermission.class);
        verify(accountPermissionRepository, times(4)).save(permCaptor.capture());
        assertTrue(permCaptor.getAllValues().stream().allMatch(p -> p.getUser().getId().equals(101L)));
    }

    @Test
    @DisplayName("createAccountByAdmin: Non-admin calling administrative creation is denied")
    void testCreateAccountByAdmin_ForbiddenForNonAdmin() {
        when(authService.getAuthenticatedUser()).thenReturn(aliceHolder);

        AdminCreateAccountRequest request = new AdminCreateAccountRequest(102L, "Sneaky Account");
        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountService.createAccountByAdmin(request));

        assertTrue(ex.getMessage().contains("Administrator role required"));
        verifyNoInteractions(userRepository);
        verifyNoInteractions(accountRepository);
    }

    @Test
    @DisplayName("createAccountByAdmin: Target user not found throws ResourceNotFoundException")
    void testCreateAccountByAdmin_TargetUserNotFound() {
        when(authService.getAuthenticatedUser()).thenReturn(adminUser);
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        AdminCreateAccountRequest request = new AdminCreateAccountRequest(999L, "Account");
        assertThrows(ResourceNotFoundException.class, () ->
                accountService.createAccountByAdmin(request));
    }

    @Test
    @DisplayName("createAccountByAdmin: Cannot create account for deactivated target user")
    void testCreateAccountByAdmin_DeactivatedUser() {
        aliceHolder.setActive(false);
        when(authService.getAuthenticatedUser()).thenReturn(adminUser);
        when(userRepository.findById(101L)).thenReturn(Optional.of(aliceHolder));

        AdminCreateAccountRequest request = new AdminCreateAccountRequest(101L, "Account");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                accountService.createAccountByAdmin(request));

        assertTrue(ex.getMessage().contains("deactivated user"));
    }

    @Test
    @DisplayName("getAccountsForCurrentUser: Account Holder receives only permitted accounts")
    void testGetAccountsForCurrentUser_AccountHolder() {
        when(authService.getAuthenticatedUser()).thenReturn(aliceHolder);
        when(accountRepository.findAccountsByUserId(101L)).thenReturn(List.of(aliceAccount));

        List<AccountResponse> accounts = accountService.getAccountsForCurrentUser();

        assertEquals(1, accounts.size());
        assertEquals("ACC-2026-A1B2C3D4E5", accounts.get(0).getAccountNumber());
        verify(accountRepository).findAccountsByUserId(101L);
        verify(accountRepository, never()).findAll();
    }

    @Test
    @DisplayName("getAccountsForCurrentUser: Auditor receives all accounts across users")
    void testGetAccountsForCurrentUser_Auditor() {
        when(authService.getAuthenticatedUser()).thenReturn(auditorUser);
        when(accountRepository.findAll()).thenReturn(List.of(aliceAccount));

        List<AccountResponse> accounts = accountService.getAccountsForCurrentUser();

        assertEquals(1, accounts.size());
        verify(accountRepository).findAll();
        verify(accountRepository, never()).findAccountsByUserId(anyLong());
    }

    @Test
    @DisplayName("getAccountById: Validates read permission and returns account details")
    void testGetAccountById_Success() {
        when(authService.getAuthenticatedUser()).thenReturn(aliceHolder);
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));

        AccountResponse response = accountService.getAccountById(201L);

        assertNotNull(response);
        assertEquals(201L, response.getId());
        verify(accountAuthorizationService).requirePermission(aliceHolder, 201L, PermissionType.CAN_VIEW_STATEMENT);
    }

    @Test
    @DisplayName("getAccountBalance: Validates read permission and returns account balance")
    void testGetAccountBalance_Success() {
        when(authService.getAuthenticatedUser()).thenReturn(aliceHolder);
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));

        AccountBalanceResponse response = accountService.getAccountBalance(201L);

        assertNotNull(response);
        assertEquals(201L, response.getAccountId());
        assertEquals(new BigDecimal("0.00"), response.getBalance());
        assertEquals("INR", response.getCurrency());
        verify(accountAuthorizationService).requirePermission(aliceHolder, 201L, PermissionType.CAN_VIEW_STATEMENT);
    }

    @Test
    @DisplayName("getAllAccountsForAdmin: Administrator lists all system accounts")
    void testGetAllAccountsForAdmin_Success() {
        when(authService.getAuthenticatedUser()).thenReturn(adminUser);
        when(accountRepository.findAll()).thenReturn(List.of(aliceAccount));

        List<AccountResponse> accounts = accountService.getAllAccountsForAdmin();

        assertEquals(1, accounts.size());
        verify(accountRepository).findAll();
    }

    @Test
    @DisplayName("getAllAccountsForAdmin: Non-admin is denied access")
    void testGetAllAccountsForAdmin_ForbiddenForNonAdmin() {
        when(authService.getAuthenticatedUser()).thenReturn(aliceHolder);

        AccountAccessDeniedException ex = assertThrows(AccountAccessDeniedException.class, () ->
                accountService.getAllAccountsForAdmin());

        assertTrue(ex.getMessage().contains("Administrator role required"));
    }

    @Test
    @DisplayName("updateAccountStatus: Administrator updates status to FROZEN without altering balance")
    void testUpdateAccountStatus_Success() {
        when(authService.getAuthenticatedUser()).thenReturn(adminUser);
        when(accountRepository.findById(201L)).thenReturn(Optional.of(aliceAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.updateAccountStatus(201L, AccountStatus.FROZEN);

        assertNotNull(response);
        assertEquals(AccountStatus.FROZEN, response.getStatus());
        assertEquals(new BigDecimal("0.00"), response.getBalance(), "Balance must remain unaffected");
    }

    @Test
    @DisplayName("updateAccountStatus: Non-admin is forbidden from updating account status")
    void testUpdateAccountStatus_ForbiddenForNonAdmin() {
        when(authService.getAuthenticatedUser()).thenReturn(aliceHolder);

        assertThrows(AccountAccessDeniedException.class, () ->
                accountService.updateAccountStatus(201L, AccountStatus.FROZEN));
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateAccountStatus: Null status throws IllegalArgumentException")
    void testUpdateAccountStatus_NullStatus() {
        when(authService.getAuthenticatedUser()).thenReturn(adminUser);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                accountService.updateAccountStatus(201L, null));

        assertTrue(ex.getMessage().contains("cannot be null"));
    }
}
