package com.jobmatchai.backend.security;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceRememberMeTest {

    private static final long EXPIRATION_MS = 86_400_000L; // 24h
    private static final long REMEMBER_ME_EXPIRATION_MS = 2_592_000_000L; // 30d

    private final JwtService jwtService = new JwtService(
            "dev-only-insecure-secret-change-me-before-deploying-to-production-0123456789",
            EXPIRATION_MS,
            REMEMBER_ME_EXPIRATION_MS,
            "dev"
    );

    @Test
    void rememberMeToken_hasMuchLongerExpirationThanDefaultToken() {
        long before = System.currentTimeMillis();
        String defaultToken = jwtService.generateToken("user@example.com", "candidate", false);
        String rememberMeToken = jwtService.generateToken("user@example.com", "candidate", true);

        Date defaultExpiry = jwtService.parseClaims(defaultToken).getExpiration();
        Date rememberMeExpiry = jwtService.parseClaims(rememberMeToken).getExpiration();

        long defaultLifetimeMs = defaultExpiry.getTime() - before;
        long rememberMeLifetimeMs = rememberMeExpiry.getTime() - before;

        assertThat(defaultLifetimeMs).isCloseTo(EXPIRATION_MS, org.assertj.core.data.Offset.offset(5_000L));
        assertThat(rememberMeLifetimeMs).isCloseTo(REMEMBER_ME_EXPIRATION_MS, org.assertj.core.data.Offset.offset(5_000L));
        assertThat(rememberMeExpiry).isAfter(defaultExpiry);
    }

    @Test
    void twoArgOverload_defaultsToNonRememberMeExpiration() {
        long before = System.currentTimeMillis();
        String token = jwtService.generateToken("user@example.com", "candidate");
        long lifetimeMs = jwtService.parseClaims(token).getExpiration().getTime() - before;

        assertThat(lifetimeMs).isCloseTo(EXPIRATION_MS, org.assertj.core.data.Offset.offset(5_000L));
    }
}
