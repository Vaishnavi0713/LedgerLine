package com.ledgerline.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.RegisterRequest;
import com.ledgerline.entity.User;
import com.ledgerline.entity.UserRole;
import com.ledgerline.repository.UserRepository;
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

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("Milestone 2: Authentication & Security Integration Tests")
class AuthenticationSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Register: Successfully registers new account holder with 201 Created")
    void testRegister_Success() throws Exception {
        RegisterRequest request = new RegisterRequest("john_doe", "john@ledgerline.test", "SecurePass123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("john_doe"))
                .andExpect(jsonPath("$.email").value("john@ledgerline.test"))
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // Verify database state directly
        User user = userRepository.findByEmail("john@ledgerline.test").orElseThrow();
        assertEquals(UserRole.ACCOUNT_HOLDER, user.getRole());
        assertTrue(user.getPasswordHash().startsWith("$2a$"), "Password must be BCrypt hashed");
        assertNotEquals("SecurePass123", user.getPasswordHash(), "Plaintext password must never be stored");
    }

    @Test
    @DisplayName("Register: Rejects duplicate email with 409 Conflict")
    void testRegister_DuplicateEmail() throws Exception {
        // holder@ledgerline.test is provisioned by bootstrap
        RegisterRequest request = new RegisterRequest("another_user", "holder@ledgerline.test", "SecurePass123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }

    @Test
    @DisplayName("Register: Rejects duplicate username with 409 Conflict")
    void testRegister_DuplicateUsername() throws Exception {
        // Register first user
        RegisterRequest first = new RegisterRequest("unique_user", "unique1@ledgerline.test", "SecurePass123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        // Attempt second registration with same username
        RegisterRequest second = new RegisterRequest("unique_user", "unique2@ledgerline.test", "SecurePass123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }

    @Test
    @DisplayName("Register: Public registration ignores client-supplied privileged role and assigns ACCOUNT_HOLDER")
    void testRegister_IgnoresRoleTampering() throws Exception {
        // Attempt privilege escalation via JSON payload
        Map<String, Object> maliciousPayload = Map.of(
                "username", "attacker_user",
                "email", "attacker@ledgerline.test",
                "password", "AttackerPassword123",
                "role", "ADMINISTRATOR"
        );

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"));

        User attacker = userRepository.findByEmail("attacker@ledgerline.test").orElseThrow();
        assertEquals(UserRole.ACCOUNT_HOLDER, attacker.getRole(),
                "Client-supplied role must be ignored during public registration");
    }

    @Test
    @DisplayName("Register: Validation rejects short passwords and invalid emails with 400 Bad Request")
    void testRegister_ValidationErrors() throws Exception {
        // Password too short (< 8 chars)
        RegisterRequest shortPass = new RegisterRequest("validuser", "valid@ledgerline.test", "short");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(shortPass)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").isNotEmpty());

        // Invalid email format
        RegisterRequest badEmail = new RegisterRequest("validuser2", "not-an-email", "SecurePassword123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badEmail)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").isNotEmpty());

        // Invalid username characters (spaces, special chars)
        RegisterRequest badUsername = new RegisterRequest("invalid name!", "valid2@ledgerline.test", "SecurePassword123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badUsername)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").isNotEmpty());
    }

    @Test
    @DisplayName("Login: Successfully authenticates and returns signed JWT with user details")
    void testLogin_Success() throws Exception {
        // Log in as provisioned demo account holder
        LoginRequest request = new LoginRequest("holder@ledgerline.test", "TestHolderPassword123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInMs").value(3600000))
                .andExpect(jsonPath("$.user.email").value("holder@ledgerline.test"))
                .andExpect(jsonPath("$.user.role").value("ACCOUNT_HOLDER"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("Login: Rejects incorrect password with 401 Unauthorized")
    void testLogin_WrongPassword() throws Exception {
        LoginRequest request = new LoginRequest("holder@ledgerline.test", "WrongPassword999");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("Login: Rejects unknown email with 401 Unauthorized")
    void testLogin_UnknownEmail() throws Exception {
        LoginRequest request = new LoginRequest("ghost@ledgerline.test", "SomePassword123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("Login: Rejects deactivated user account with 401 Unauthorized")
    void testLogin_DeactivatedUser() throws Exception {
        // Deactivate holder
        User holder = userRepository.findByEmail("holder@ledgerline.test").orElseThrow();
        holder.setActive(false);
        userRepository.saveAndFlush(holder);

        LoginRequest request = new LoginRequest("holder@ledgerline.test", "TestHolderPassword123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("User account is deactivated"));
    }

    @Test
    @DisplayName("Profile: GET /api/auth/me returns authenticated user info when Bearer token is provided")
    void testGetMe_Authenticated() throws Exception {
        // 1. Login to obtain token
        LoginRequest loginRequest = new LoginRequest("holder@ledgerline.test", "TestHolderPassword123");
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginJson.get("token").asText();

        // 2. Call /api/auth/me with Bearer token
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("holder@ledgerline.test"))
                .andExpect(jsonPath("$.role").value("ACCOUNT_HOLDER"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("Profile: GET /api/auth/me returns 401 Unauthorized when unauthenticated")
    void testGetMe_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Profile: GET /api/auth/me returns 401 Unauthorized when invalid token is provided")
    void testGetMe_InvalidToken() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer invalid.malformed.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
}
