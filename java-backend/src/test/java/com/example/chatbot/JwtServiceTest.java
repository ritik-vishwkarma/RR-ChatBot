package com.example.chatbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
    private static final String ISSUER = "chatbot-test";
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    private JwtService jwt;

    @BeforeEach
    void setUp() {
        jwt = new JwtService(SECRET, ISSUER, 900);
    }

    @Test
    void issuedTokenVerifiesAndReturnsSubject() {
        JwtService.Issued issued = jwt.issue("qa-user@example.com");

        assertEquals(900, issued.expiresIn());
        assertEquals("qa-user@example.com", jwt.verifyAndGetSubject(issued.token()));
    }

    @Test
    void malformedTokenIsRejected() {
        assertNull(jwt.verifyAndGetSubject("not.a.valid.jwt"));
    }

    @Test
    void blankSubjectCannotBeUsedToIssueValidAuthentication() {
        JwtService.Issued issued = jwt.issue("   ");

        assertNull(jwt.verifyAndGetSubject(issued.token()));
    }

    @Test
    void rejectsSecretThatIsNotValidBase64() {
        assertThrows(IllegalStateException.class,
                () -> new JwtService("not base64!", ISSUER, 900));
    }

    @Test
    void rejectsDecodedSecretShorterThan32Bytes() {
        String shortSecret = Base64.getEncoder().encodeToString(new byte[31]);

        assertThrows(IllegalStateException.class,
                () -> new JwtService(shortSecret, ISSUER, 900));
    }

    @Test
    void rejectsAccessLifetimeOutsideSupportedRange() {
        assertThrows(IllegalStateException.class,
                () -> new JwtService(SECRET, ISSUER, 59));
        assertThrows(IllegalStateException.class,
                () -> new JwtService(SECRET, ISSUER, 3601));
    }
}
