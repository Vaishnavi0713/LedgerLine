package com.ledgerline.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.dto.AdminCreateUserRequest;
import com.ledgerline.dto.LoginRequest;
import com.ledgerline.entity.UserRole;
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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Milestone 2: Role-Based Access Control (RBAC) Integration Tests")
class RoleBasedAccessControlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String holderToken;
    private String auditorToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = obtainToken("admin@ledgerline.test", "TestAdminPassword123");
        holderToken = obtainToken("holder@ledgerline.test", "TestHolderPassword123");
        auditorToken = obtainToken("auditor@ledgerline.test", "TestAuditorPassword123");
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
    // Admin Endpoint Access Rules (/api/admin/**)
    // =========================================================================

    @Test
    @DisplayName("Admin Endpoints: ADMINISTRATOR role has full access to /api/admin/system-info")
    void testAdminEndpoint_AdminAllowed() throws Exception {
        mockMvc.perform(get("/api/admin/system-info")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("LedgerLine"))
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.totalUsers", greaterThanOrEqualTo(3)));
    }

    @Test
    @DisplayName("Admin Endpoints: ACCOUNT_HOLDER role is denied access to /api/admin/system-info with 403 Forbidden")
    void testAdminEndpoint_AccountHolderForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/system-info")
                        .header("Authorization", "Bearer " + holderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("Admin Endpoints: AUDITOR role is denied access to /api/admin/system-info with 403 Forbidden")
    void testAdminEndpoint_AuditorForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/system-info")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("Admin Endpoints: Unauthenticated request to /api/admin/system-info returns 401 Unauthorized")
    void testAdminEndpoint_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/system-info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Admin Users: ADMINISTRATOR can list users via GET /api/admin/users")
    void testAdminListUsers_AdminAllowed() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(3)));
    }

    @Test
    @DisplayName("Admin Users: ACCOUNT_HOLDER is denied listing users via GET /api/admin/users with 403 Forbidden")
    void testAdminListUsers_HolderForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + holderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin Provisioning: ADMINISTRATOR can provision AUDITOR via POST /api/admin/users")
    void testAdminCreateUser_AdminCanCreateAuditor() throws Exception {
        AdminCreateUserRequest request = new AdminCreateUserRequest(
                "new_auditor",
                "new_auditor@ledgerline.test",
                "SecureAuditorPass123",
                UserRole.AUDITOR
        );

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("new_auditor"))
                .andExpect(jsonPath("$.email").value("new_auditor@ledgerline.test"))
                .andExpect(jsonPath("$.role").value("AUDITOR"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("Admin Provisioning: ACCOUNT_HOLDER is denied provisioning users via POST /api/admin/users with 403 Forbidden")
    void testAdminCreateUser_HolderForbidden() throws Exception {
        AdminCreateUserRequest request = new AdminCreateUserRequest(
                "priv_escalate",
                "escalate@ledgerline.test",
                "Password123",
                UserRole.ADMINISTRATOR
        );

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + holderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // =========================================================================
    // Auditor Endpoint Access Rules (/api/audit/**)
    // =========================================================================

    @Test
    @DisplayName("Audit Endpoints: AUDITOR role has read access to /api/audit/records")
    void testAuditEndpoint_AuditorAllowed() throws Exception {
        mockMvc.perform(get("/api/audit/records")
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.auditorAccess").value(true))
                .andExpect(jsonPath("$.readOnly").value(true));
    }

    @Test
    @DisplayName("Audit Endpoints: ADMINISTRATOR role has read access to /api/audit/records")
    void testAuditEndpoint_AdminAllowed() throws Exception {
        mockMvc.perform(get("/api/audit/records")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Audit Endpoints: ACCOUNT_HOLDER role is denied access to /api/audit/records with 403 Forbidden")
    void testAuditEndpoint_AccountHolderForbidden() throws Exception {
        mockMvc.perform(get("/api/audit/records")
                        .header("Authorization", "Bearer " + holderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("Audit Endpoints: Write operations (POST /api/audit/records) are forbidden for AUDITOR (strictly read-only)")
    void testAuditEndpoint_WriteForbiddenForAuditor() throws Exception {
        mockMvc.perform(post("/api/audit/records")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"UNAUTHORIZED_WRITE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Audit Endpoints: Write operations (POST /api/audit/records) are forbidden even for ADMINISTRATOR")
    void testAuditEndpoint_WriteForbiddenForAdmin() throws Exception {
        mockMvc.perform(post("/api/audit/records")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"ADMIN_WRITE_ATTEMPT\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
