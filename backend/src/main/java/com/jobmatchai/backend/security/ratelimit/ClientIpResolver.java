package com.jobmatchai.backend.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// X-Forwarded-For is set by the client itself unless something in front of this app (a reverse
// proxy / load balancer) overwrites it, so trusting it unconditionally would let any caller pick
// its own rate-limit identity and bypass IP-based throttling entirely. app.proxy.trust-x-forwarded-for
// must be explicitly turned on for deployments that actually sit behind such a proxy.
@Component
public class ClientIpResolver {

    @Value("${app.proxy.trust-x-forwarded-for:false}")
    private boolean trustXForwardedFor;

    public String resolve(HttpServletRequest request) {
        if (trustXForwardedFor) {
            String header = request.getHeader("X-Forwarded-For");
            if (header != null && !header.isBlank()) {
                // Leftmost entry is the original client; a trusted proxy appends to the right.
                String candidate = header.split(",")[0].trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
