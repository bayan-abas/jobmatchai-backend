package com.jobmatchai.backend.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    @Test
    void resolve_usesRemoteAddr_whenXForwardedForNotTrusted() {
        ClientIpResolver resolver = new ClientIpResolver();
        ReflectionTestUtils.setField(resolver, "trustXForwardedFor", false);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void resolve_usesFirstXForwardedForEntry_whenProxyTrustExplicitlyEnabled() {
        ClientIpResolver resolver = new ClientIpResolver();
        ReflectionTestUtils.setField(resolver, "trustXForwardedFor", true);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 70.41.3.18, 150.172.238.178");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void resolve_fallsBackToRemoteAddr_whenTrustedButHeaderMissing() {
        ClientIpResolver resolver = new ClientIpResolver();
        ReflectionTestUtils.setField(resolver, "trustXForwardedFor", true);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.2");
    }
}
