package com.ledgerline.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.dto.AdminCreateAccountRequest;
import com.ledgerline.dto.CreateAccountRequest;
import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.RegisterRequest;
import com.ledgerline.dto.UpdateAccountStatusRequest;
import com.ledgerline.entity.Account;
import com.ledgerline.entity.AccountPermission;
import com.ledgerline.entity.AccountStatus;
import com.ledgerline.entity.PermissionType;
import com.ledgerline.entity.User;
import com.ledgerline.repository.AccountPermissionRepository;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Milestone 3: Account Management Integration Tests")
class AccountManagementIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountPermissionRepository accountPermissionRepository;

    private String adminToken;
    private String holderToken;
    private String auditorToken;
    private User holderUser;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = obtainToken("admin@ledgerline.test", "TestAdminPassword123");
        holderToken = obtainToken("holder@ledgerline.test", "TestHolderPassword123");
        auditorToken = obtainToken("auditor@ledgerline.test", "TestAuditorPassword123");
        holderUser = userRepository.findByEmail("holder@ledgerline.test").orElseThrow();
    }

    private String obtainToken(String email, String password) throws Exception {
        LoginRequest request = new LoginRequest(email, password);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(result.getResponse().getContentAsString());
        return jsonNode.get("token").asText();
    }

    // =========================================================================
    // 1. Account Creation Tests
    // =========================================================================

    @Test
    @DisplayName("Create Account: Account Holder creates account starting at 0.00 INR with permissions")
    void testAccountHolder_CreateAccount_Success() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("Primary Checking", "INR");

        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.accountNumber", startsWith("ACC-")))
                .andExpect(jsonPath("$.name").value("Primary Checking"))
                .andExpect(jsonPath("$.accountType").value("CUSTOMER"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        Long accountId = json.get("id").asLong();

        // Verify database state: balance strictly 0.00
        Account savedAccount = accountRepository.findById(accountId).orElseThrow();
        assertEquals(new BigDecimal("0.00"), savedAccount.getBalance());
        assertEquals("INR", savedAccount.getCurrency());
        assertEquals(AccountStatus.ACTIVE, savedAccount.getStatus());

        // Verify 4 account_permissions rows exist for holderUser
        List<AccountPermission> permissions = accountPermissionRepository.findByUserIdAndAccountId(holderUser.getId(), accountId);
        assertEquals(4, permissions.size());
        assertTrue(permissions.stream().anyMatch(p -> p.getPermission() == PermissionType.CAN_DEPOSIT));
        assertTrue(permissions.stream().anyMatch(p -> p.getPermission() == PermissionType.CAN_WITHDRAW));
        assertTrue(permissions.stream().anyMatch(p -> p.getPermission() == PermissionType.CAN_TRANSFER));
        assertTrue(permissions.stream().anyMatch(p -> p.getPermission() == PermissionType.CAN_VIEW_STATEMENT));
    }

    @Test
    @DisplayName("Create Account: Client cannot supply balance or owner ID to bypass rules")
    void testAccountHolder_CreateAccount_ClientCannotSupplyBalanceOrOwner() throws Exception {
        // Malicious client tries to inject initial balance, statement sequence, and spoofed user ID
        Map<String, Object> maliciousPayload = Map.of(
                "name", "Exploit Attempt Account",
                "balance", 99999.00,
                "initialBalance", 5000.00,
                "userId", 1,
                "ownerId", 999,
                "currency", "INR"
        );

        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0.00))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        Long accountId = json.get("id").asLong();

        // Verify database: balance is strictly 0.00
        Account savedAccount = accountRepository.findById(accountId).orElseThrow();
        assertEquals(new BigDecimal("0.00"), savedAccount.getBalance());

        // Verify permissions belong to holderUser (from JWT), NOT userId 1
        List<AccountPermission> permissions = accountPermissionRepository.findByUserIdAndAccountId(holderUser.getId(), accountId);
        assertEquals(4, permissions.size());
    }

    @Test
    @DisplayName("Create Account: Auditor is forbidden from creating accounts with 403 Forbidden")
    void testAccountHolder_CreateAccount_AuditorDenied() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest("Auditor Account");

        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Create Account: Rejects unsupported currency with 400 Bad Request")
    void testAccountHolder_CreateAccount_InvalidCurrency() throws Exception {
        Map<String, Object> payload = Map.of(
                "name", "USD Checking",
                "currency", "USD"
        );

        mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.currency").value("Currency must be INR"));
    }

    // =========================================================================
    // 2. Administrator Account Creation Tests
    // =========================================================================

    @Test
    @DisplayName("Admin Create Account: Administrator creates account for existing target user")
    void testAdmin_CreateAccountForUser_Success() throws Exception {
        AdminCreateAccountRequest request = new AdminCreateAccountRequest(holderUser.getId(), "Payroll Checking");

        MvcResult result = mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Payroll Checking"))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        Long accountId = json.get("id").asLong();

        // Verify permissions assigned to target user (holderUser)
        List<AccountPermission> permissions = accountPermissionRepository.findByUserIdAndAccountId(holderUser.getId(), accountId);
        assertEquals(4, permissions.size());
    }

    @Test
    @DisplayName("Admin Create Account: ACCOUNT_HOLDER is forbidden from using admin creation endpoint")
    void testAdmin_CreateAccount_ForbiddenForAccountHolder() throws Exception {
        AdminCreateAccountRequest request = new AdminCreateAccountRequest(holderUser.getId(), "Privilege Escalation");

        mockMvc.perform(post("/api/admin/accounts")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 3. Account Retrieval & IDOR Prevention Tests
    // =========================================================================

    @Test
    @DisplayName("Account List: Returns only authorized accounts for the authenticated account holder")
    void testAccountList_ReturnsOnlyAuthorizedAccounts() throws Exception {
        // Register and login User B (Bob)
        RegisterRequest registerB = new RegisterRequest("bob_holder", "bob@ledgerline.test", "SecureBobPass123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerB)))
                .andExpect(status().isCreated());

        String bobToken = obtainToken("bob@ledgerline.test", "SecureBobPass123");

        // Holder creates Account 1
        CreateAccountRequest req1 = new CreateAccountRequest("Holder Account 1");
        MvcResult res1 = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andReturn();
        Long holderAccountId = objectMapper.readTree(res1.getResponse().getContentAsString()).get("id").asLong();

        // Bob creates Account 2
        CreateAccountRequest req2 = new CreateAccountRequest("Bob Account 2");
        MvcResult res2 = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn();
        Long bobAccountId = objectMapper.readTree(res2.getResponse().getContentAsString()).get("id").asLong();

        // Holder's GET /api/accounts must contain Account 1 and NOT Account 2
        mockMvc.perform(get("/api/accounts")
                        .header("Authorization", "Bearer " + holderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(holderAccountId.intValue())))
                .andExpect(jsonPath("$[*].id", not(hasItem(bobAccountId.intValue()))));

        // Bob's GET /api/accounts must contain Account 2 and NOT Account 1
        mockMvc.perform(get("/api/accounts")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(bobAccountId.intValue())))
                .andExpect(jsonPath("$[*].id", not(hasItem(holderAccountId.intValue()))));
    }

    @Test
    @DisplayName("Account Retrieval: Cross-user access is denied with 403 Forbidden (IDOR Defense)")
    void testAccountRetrieval_CrossUserAccessDenied() throws Exception {
        // Register User B
        RegisterRequest registerB = new RegisterRequest("carol_holder", "carol@ledgerline.test", "SecureCarolPass123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerB)))
                .andExpect(status().isCreated());
        String carolToken = obtainToken("carol@ledgerline.test", "SecureCarolPass123");

        // Holder creates an account
        CreateAccountRequest req = new CreateAccountRequest("Holder Private Account");
        MvcResult res = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        Long accountId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        // Carol attempts to access Holder's account details -> 403 Forbidden
        mockMvc.perform(get("/api/accounts/" + accountId)
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // Carol attempts to access Holder's balance -> 403 Forbidden
        mockMvc.perform(get("/api/accounts/" + accountId + "/balance")
                        .header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Account Retrieval: Non-existent account denied with 403 without leaking existence")
    void testAccountRetrieval_NonExistentAccountDeniedWithoutLeaking() throws Exception {
        mockMvc.perform(get("/api/accounts/999999")
                        .header("Authorization", "Bearer " + holderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message", containsString("Access denied")));
    }

    @Test
    @DisplayName("Account Balance: Retrieves balance with 200 OK for authorized holder")
    void testAccountBalance_Success() throws Exception {
        CreateAccountRequest req = new CreateAccountRequest("Balance Checking");
        MvcResult res = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        Long accountId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/accounts/" + accountId + "/balance")
                        .header("Authorization", "Bearer " + holderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.asOf").isNotEmpty());
    }

    @Test
    @DisplayName("Auditor Inspection: Auditor can read accounts and balances across users")
    void testAuditor_ReadOnlyInspectionAllowed() throws Exception {
        // Holder creates account
        CreateAccountRequest req = new CreateAccountRequest("Auditable Account");
        MvcResult res = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        Long accountId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        // Auditor inspects all accounts via GET /api/accounts
        mockMvc.perform(get("/api/accounts")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(accountId.intValue())));

        // Auditor inspects specific account details
        mockMvc.perform(get("/api/accounts/" + accountId)
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId))
                .andExpect(jsonPath("$.name").value("Auditable Account"));

        // Auditor inspects account balance
        mockMvc.perform(get("/api/accounts/" + accountId + "/balance")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0.00));
    }

    // =========================================================================
    // 4. Administrative Account Management & Status Tests
    // =========================================================================

    @Test
    @DisplayName("Admin Accounts: Administrator lists all accounts across all users")
    void testAdmin_ListAllAccounts() throws Exception {
        mockMvc.perform(get("/api/admin/accounts")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Admin Status: Administrator updates account status to FROZEN and reactivates to ACTIVE")
    void testAdmin_UpdateAccountStatus_FreezeAndReactivate() throws Exception {
        // Holder creates account
        CreateAccountRequest req = new CreateAccountRequest("Status Transition Account");
        MvcResult res = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        Long accountId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();

        // Admin freezes account
        UpdateAccountStatusRequest freezeReq = new UpdateAccountStatusRequest(AccountStatus.FROZEN);
        mockMvc.perform(patch("/api/admin/accounts/" + accountId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(freezeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId))
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.balance").value(0.00));

        // Verify in DB
        assertEquals(AccountStatus.FROZEN, accountRepository.findById(accountId).orElseThrow().getStatus());

        // Admin reactivates account
        UpdateAccountStatusRequest activateReq = new UpdateAccountStatusRequest(AccountStatus.ACTIVE);
        mockMvc.perform(patch("/api/admin/accounts/" + accountId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(activateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Verify in DB
        assertEquals(AccountStatus.ACTIVE, accountRepository.findById(accountId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Admin Status: ACCOUNT_HOLDER is forbidden from modifying account status with 403 Forbidden")
    void testAdmin_UpdateAccountStatus_ForbiddenForAccountHolder() throws Exception {
        UpdateAccountStatusRequest req = new UpdateAccountStatusRequest(AccountStatus.FROZEN);

        mockMvc.perform(patch("/api/admin/accounts/1/status")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: Unauthenticated requests return 401 Unauthorized")
    void testUnauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/accounts"))
                .andExpect(status().isUnauthorized());
    }
}
