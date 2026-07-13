package com.jobmatchai.backend.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Closes a real gap in the app's stateless-JWT design (deliberately no DB lookup per request -
// see JwtAuthenticationFilter's comment on why): a token issued for an account that's since been
// deleted, or whose password has since changed, previously stayed valid and kept authenticating
// requests for up to app.jwt.expiration-ms (24h default) with nothing anyone could do about it.
//
// This is an in-memory "reject tokens issued before this instant" record per email, checked by
// JwtAuthenticationFilter on every request WITHOUT a database round trip - the check is a single
// map lookup plus a timestamp comparison, so it doesn't reintroduce the per-request DB cost the
// filter was written to avoid. The tradeoff, and why that's acceptable here: this is in-memory
// only, so a revocation doesn't survive an app restart and wouldn't be shared across multiple
// app instances behind a load balancer. For a single-instance deployment (this project's actual
// scale) that just means "restarting the app re-trusts every not-yet-expired token issued before
// the restart, exactly as if this feature didn't exist" - never worse than today's behavior, and
// meaningfully better for the common case (password change / account deletion during a single
// long-running app lifetime, no restart involved).
@Service
public class TokenRevocationService {

    private final Map<String, Instant> revokedBeforeByEmail = new ConcurrentHashMap<>();

    public void revokeTokensIssuedBefore(String email, Instant instant) {
        revokedBeforeByEmail.merge(email, instant,
                (existing, incoming) -> incoming.isAfter(existing) ? incoming : existing);
    }

    public boolean isRevoked(String email, Date issuedAt) {
        Instant revokedBefore = revokedBeforeByEmail.get(email);
        return revokedBefore != null && issuedAt != null && issuedAt.toInstant().isBefore(revokedBefore);
    }
}
