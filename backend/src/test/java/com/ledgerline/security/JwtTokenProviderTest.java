package com.ledgerline.security;

import com.ledgerline.entity.User;
import com.ledgerline.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Milestone 2: JWT Token Provider Unit Tests")
class JwtTokenProviderTest {

    private static final String TEST_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"; // 256 bits (64 hex chars)
    private static final long EXPIRATION_MS = 3600000; // 1 hour

    private JwtTokenProvider jwtTokenProvider;
    private User testUser;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(TEST_SECRET, EXPIRATION_MS);
        jwtTokenProvider.init();

        testUser = new User("alice", "alice@example.com", "$2a$12$hashedPasswordExample", UserRole.ACCOUNT_HOLDER);
        testUser.setId(42L);
    }

    @Test
    @DisplayName("Generate Token: successfully creates signed JWT with required claims")
    void testGenerateTokenAndValidate() {
        String token = jwtTokenProvider.generateToken(testUser);

        assertNotNull(token);
        assertFalse(token.isBlank());
        assertTrue(jwtTokenProvider.validateToken(token));

        Claims claims = jwtTokenProvider.extractClaims(token);
        assertEquals("alice@example.com", claims.getSubject());
        assertEquals(42L, jwtTokenProvider.extractUserId(token));
        assertEquals("alice@example.com", jwtTokenProvider.extractEmail(token));
        assertEquals("ACCOUNT_HOLDER", jwtTokenProvider.extractRole(token));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()));
    }

    @Test
    @DisplayName("Validation: Rejects expired JWT token")
    void testValidateToken_Expired() {
        // Provider configured with negative expiration
        JwtTokenProvider expiredProvider = new JwtTokenProvider(TEST_SECRET, -10000L);
        expiredProvider.init();

        String expiredToken = expiredProvider.generateToken(testUser);

        assertFalse(jwtTokenProvider.validateToken(expiredToken), "Expired token must be rejected");
    }

    @Test
    @DisplayName("Validation: Rejects tampered JWT payload")
    void testValidateToken_TamperedPayload() {
        String token = jwtTokenProvider.generateToken(testUser);
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);

        // Tamper with payload (middle component)
        String tamperedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"hacked@example.com\",\"role\":\"ADMINISTRATOR\"}".getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertFalse(jwtTokenProvider.validateToken(tamperedToken), "Tampered payload token must fail signature verification");
    }

    @Test
    @DisplayName("Validation: Rejects token signed with a different key")
    void testValidateToken_InvalidSignature() {
        String differentSecret = "differentSecretKeyThatIsAtLeast256BitsLongForHMACSHA256TestingXYZ987";
        JwtTokenProvider otherProvider = new JwtTokenProvider(differentSecret, EXPIRATION_MS);
        otherProvider.init();

        String tokenSignedWithDifferentKey = otherProvider.generateToken(testUser);

        assertFalse(jwtTokenProvider.validateToken(tokenSignedWithDifferentKey),
                "Token signed with different secret key must be rejected");
    }

    @Test
    @DisplayName("Validation: Rejects malformed and empty tokens")
    void testValidateToken_MalformedAndEmpty() {
        assertFalse(jwtTokenProvider.validateToken(null));
        assertFalse(jwtTokenProvider.validateToken(""));
        assertFalse(jwtTokenProvider.validateToken("   "));
        assertFalse(jwtTokenProvider.validateToken("not.a.valid.jwt"));
        assertFalse(jwtTokenProvider.validateToken("eyJhbGciOiJIUzI1NiJ9.invalid.signature"));
    }

    @Test
    @DisplayName("Security: Rejects secret key with less than 256 bits (32 bytes)")
    void testInit_FailsOnShortKey() {
        String shortSecret = "too-short-secret-key-16b";
        JwtTokenProvider shortProvider = new JwtTokenProvider(shortSecret, EXPIRATION_MS);

        IllegalStateException ex = assertThrows(IllegalStateException.class, shortProvider::init);
        assertTrue(ex.getMessage().contains("at least 256 bits"), "Error message should mention minimum 256 bits requirement");
    }

    @Test
    @DisplayName("Security: Rejects null or empty secret key")
    void testInit_FailsOnNullOrEmptyKey() {
        JwtTokenProvider nullProvider = new JwtTokenProvider(null, EXPIRATION_MS);
        assertThrows(IllegalStateException.class, nullProvider::init);

        JwtTokenProvider blankProvider = new JwtTokenProvider("   ", EXPIRATION_MS);
        assertThrows(IllegalStateException.class, blankProvider::init);
    }
}
