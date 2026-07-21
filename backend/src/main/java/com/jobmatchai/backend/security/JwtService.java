package com.jobmatchai.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    // Must match application.properties' app.jwt.secret default exactly - that default is
    // committed to git (public to anyone with repo access), so letting it silently sign
    // production tokens would let anyone forge a valid JWT for any email/role.
    private static final String INSECURE_DEFAULT_SECRET =
            "dev-only-insecure-secret-change-me-before-deploying-to-production-0123456789";

    private final SecretKey signingKey;
    private final long expirationMs;
    private final long rememberMeExpirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs,
            @Value("${app.jwt.remember-me-expiration-ms}") long rememberMeExpirationMs,
            @Value("${app.environment:dev}") String environment
    ) {
        // Fails startup outright rather than silently running with a publicly-known signing
        // key - JWT_SECRET being unset in a "prod" deployment previously had no signal at all
        // beyond a comment in application.properties that's easy to miss.
        if ("prod".equals(environment) && INSECURE_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "app.environment=prod but JWT_SECRET was never set - refusing to start with the "
                            + "publicly-known default signing key. Set JWT_SECRET to a long random value "
                            + "(e.g. `openssl rand -base64 48`) before deploying.");
        }

        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.rememberMeExpirationMs = rememberMeExpirationMs;
    }

    public String generateToken(String email, String role) {
        return generateToken(email, role, false);
    }

    public String generateToken(String email, String role, boolean rememberMe) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + (rememberMe ? rememberMeExpirationMs : expirationMs));

        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public Date extractIssuedAt(String token) {
        return parseClaims(token).getIssuedAt();
    }

    public boolean isValid(String token) {
        try {
            Claims claims = parseClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }
}
