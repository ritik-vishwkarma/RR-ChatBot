package com.example.chatbot;

import java.time.Instant;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.stereotype.Service;

/** Uses Spring Security/Nimbus for JWT signing and verification with a fixed HS256 algorithm. */
@Service
class JwtService {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final String issuer;
    private final long accessSeconds;

    JwtService(
        @Value("${security.jwt.secret}") 
        String encodedSecret,
        @Value("${security.jwt.issuer:chatbot-api}") 
        String issuer,
        @Value("${security.jwt.access-seconds:900}") 
        long accessSeconds
    ) {
        final byte[] secret;

        try { 
            secret = Base64.getDecoder().decode(encodedSecret); 
        } catch (IllegalArgumentException e) { 
            throw new IllegalStateException("JWT_SECRET must be a Base64-encoded random secret", e); 
        }

        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must decode to at least 32 random bytes");
        }

        if (accessSeconds < 60 || accessSeconds > 3600) {
            throw new IllegalStateException("JWT access lifetime must be between 60 and 3600 seconds");
        }

        this.issuer = issuer;
        this.accessSeconds = accessSeconds;

        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));

        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));

        this.decoder = nimbus;
    }

    record Issued(String token, long expiresIn) {}

    Issued issue(String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet
                                .builder()
                                .issuer(issuer)
                                .subject(subject)
                                .issuedAt(now)
                                .expiresAt(now.plusSeconds(accessSeconds))
                                .build();

        String token = encoder
                        .encode(
                            JwtEncoderParameters.from(
                                JwsHeader
                                .with(
                                    MacAlgorithm.HS256
                                )
                                .type("JWT")
                                .build(), 
                                claims
                            )
                        )
                        .getTokenValue();

        return new Issued(token, accessSeconds);
    }

    /** Verifies a JWT and returns the subject if valid, or null otherwise. */
    String verifyAndGetSubject(String token) {
        try {
            Jwt decoded = decoder.decode(token);
            Instant now = Instant.now();
            Instant issuedAt = decoded.getIssuedAt();
            Instant expiresAt = decoded.getExpiresAt();
            String subject = decoded.getSubject();

            if (!issuer.equals( String.valueOf( decoded.getClaims().get("iss") ) ) 
                || subject == null || subject.isBlank()
                || issuedAt == null || expiresAt == null 
                || !expiresAt.isAfter(now)
                || issuedAt.isAfter(now.plusSeconds(30)) 
                || !expiresAt.isAfter(issuedAt)
                || (expiresAt.getEpochSecond() - issuedAt.getEpochSecond()) > accessSeconds) {
                    return null;
                }

            return subject;
        } catch (JwtException | IllegalArgumentException e) { 
            return null; 
        }
    }
}
