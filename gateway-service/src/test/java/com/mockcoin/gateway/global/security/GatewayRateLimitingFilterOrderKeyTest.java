package com.mockcoin.gateway.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.observability.MockcoinMetrics;
import com.mockcoin.shared.security.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;

class GatewayRateLimitingFilterOrderKeyTest {

    @Test
    void orderRateLimitKeyUsesUserIdAcrossDifferentTokens() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(1000);
        properties.setOrderRequestsPerMinute(5);

        RecordingRateLimiter limiter = new RecordingRateLimiter();
        StubTokenAuthenticator authenticator = new StubTokenAuthenticator();
        authenticator.put("Bearer device-A", 1234L);
        authenticator.put("Bearer device-B", 1234L);
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics, authenticator);

        FilterChain chain = (req, res) -> {
        };

        invokeOrderPost(filter, chain, "Bearer device-A");
        invokeOrderPost(filter, chain, "Bearer device-B");

        List<String> orderKeys = limiter.keys.stream().filter(key -> key.startsWith("order:")).toList();
        assertThat(orderKeys).hasSize(2);
        assertThat(orderKeys).allMatch(key -> key.equals("order:uid:1234"));
    }

    @Test
    void affirmativeRateLimitAuthenticationIsStoredForTheRequest() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(1000);
        properties.setOrderRequestsPerMinute(5);

        RecordingRateLimiter limiter = new RecordingRateLimiter();
        StubTokenAuthenticator authenticator = new StubTokenAuthenticator();
        authenticator.put("Bearer request-token", 777L);
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics, authenticator);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("Authorization", "Bearer request-token");
        AtomicReference<Object> observedUserId = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                observedUserId.set(req.getAttribute("com.mockcoin.gateway.authenticatedUserId")));

        assertThat(observedUserId.get()).isEqualTo(777L);
    }

    @Test
    void orderRateLimitKeyFallsBackToIpWhenAuthorizationMissing() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(1000);
        properties.setOrderRequestsPerMinute(5);

        RecordingRateLimiter limiter = new RecordingRateLimiter();
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics, NullTokenAuthenticator.INSTANCE);

        FilterChain chain = (req, res) -> {
        };

        invokeOrderPost(filter, chain, null);

        List<String> orderKeys = limiter.keys.stream().filter(key -> key.startsWith("order:")).toList();
        assertThat(orderKeys).containsExactly("order:ip:10.0.0.5");
    }

    @Test
    void orderRateLimitKeyUsesHashedTokenWhenAuthenticatorCannotResolveUser() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(1000);
        properties.setOrderRequestsPerMinute(5);

        RecordingRateLimiter limiter = new RecordingRateLimiter();
        StubTokenAuthenticator authenticator = new StubTokenAuthenticator();
        // No mapping registered, so resolveUserIdQuietly returns null.
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics, authenticator);

        FilterChain chain = (req, res) -> {
        };

        invokeOrderPost(filter, chain, "Bearer unknown");

        List<String> orderKeys = limiter.keys.stream().filter(key -> key.startsWith("order:")).toList();
        assertThat(orderKeys).hasSize(1);
        assertThat(orderKeys.getFirst()).startsWith("order:token:");
        assertThat(orderKeys.getFirst()).doesNotContain("unknown");
    }

    @Test
    void nicknameUpdateUsesDedicatedMinuteUserBucketOnly() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(1000);
        properties.setAuthenticatedRequestsPerMinute(1000);
        properties.setNicknameRequestsPerMinute(10);

        RecordingRateLimiter limiter = new RecordingRateLimiter();
        StubTokenAuthenticator authenticator = new StubTokenAuthenticator();
        authenticator.put("Bearer profile-token", 777L);
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics, authenticator);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/me/profile/nickname");
        request.setRemoteAddr("10.0.0.6");
        request.addHeader("Authorization", "Bearer profile-token");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(limiter.keys).contains("profile:nickname:minute:uid:777");
        assertThat(limiter.keys).noneMatch(key -> key.startsWith("profile:nickname:day:"));
    }

    @Test
    void marketCandleReadUsesDedicatedReadBucket() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(1000);
        properties.setMarketCandleRequestsPerMinute(120);

        RecordingRateLimiter limiter = new RecordingRateLimiter();
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics, NullTokenAuthenticator.INSTANCE);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/markets/KRW-BTC/candles");
        request.setRemoteAddr("10.0.0.7");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(limiter.keys).contains("market:candles:ip:10.0.0.7");
    }

    @Test
    void adminIapWriteUsesSensitiveAdminBucket() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(1000);
        properties.setAdminSensitiveRequestsPerMinute(5);

        RecordingRateLimiter limiter = new RecordingRateLimiter();
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics, NullTokenAuthenticator.INSTANCE);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/api/v1/admin-console/iap/grant");
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Mockcoin-Admin-Token", "local-dev-admin-token");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(limiter.keys).contains("admin:sensitive:principal:admin");
    }

    @Test
    void invalidAdminTokenCannotCreateRotatingAdminBucket() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(1000);
        properties.setAdminSensitiveRequestsPerMinute(5);

        RecordingRateLimiter limiter = new RecordingRateLimiter();
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics, NullTokenAuthenticator.INSTANCE);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/api/v1/admin-console/iap/grant");
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Mockcoin-Admin-Token", "attacker-controlled-token");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(limiter.keys).contains("admin:sensitive:ip:10.0.0.9");
        assertThat(limiter.keys).noneMatch(key -> key.startsWith("admin:sensitive:token:"));
    }

    @Test
    void validAdminSessionCookieCanCreateStableSessionBucket() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "admin",
                        null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN_CONSOLE"))
                )
        );
        try {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MockcoinMetrics metrics = new MockcoinMetrics(registry);
            GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
            properties.setRateLimitEnabled(true);
            properties.setIpRequestsPerMinute(1000);
            properties.setAdminSensitiveRequestsPerMinute(5);

            RecordingRateLimiter limiter = new RecordingRateLimiter();
            GatewayRateLimitingFilter filter = filter(
                    properties, limiter, metrics, NullTokenAuthenticator.INSTANCE
            );

            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/api/v1/admin-console/iap/grant");
            request.setRemoteAddr("10.0.0.10");
            request.setCookies(new jakarta.servlet.http.Cookie("ADMIN_SESSION", "stable-session-id"));
            request.addHeader("X-Mockcoin-Admin-Token", "attacker-controlled-token");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            });

            assertThat(limiter.keys).contains("admin:sensitive:principal:admin");
            assertThat(limiter.keys).noneMatch(key -> key.startsWith("admin:sensitive:token:"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void matrixParameterPathStillUsesOrderBucket() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(1000);
        properties.setOrderRequestsPerMinute(5);

        RecordingRateLimiter limiter = new RecordingRateLimiter();
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics, NullTokenAuthenticator.INSTANCE);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders;foo=1");
        request.setRemoteAddr("10.0.0.11");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(limiter.keys).contains("order:ip:10.0.0.11");
    }

    @Test
    void matrixParameterAdminPathStillUsesSensitiveBucket() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(1000);
        properties.setAdminSensitiveRequestsPerMinute(5);

        RecordingRateLimiter limiter = new RecordingRateLimiter();
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics, NullTokenAuthenticator.INSTANCE);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/api/v1/admin-console/iap;foo=1/grant");
        request.setRemoteAddr("10.0.0.12");
        request.addHeader("X-Mockcoin-Admin-Token", "local-dev-admin-token");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(limiter.keys).contains("admin:sensitive:principal:admin");
    }

    private static void invokeOrderPost(GatewayRateLimitingFilter filter, FilterChain chain, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        request.setRemoteAddr("10.0.0.5");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
    }

    private static GatewayRateLimitingFilter filter(
            GatewaySecurityHardeningProperties properties,
            RateLimiter limiter,
            MockcoinMetrics metrics,
            GatewayTokenAuthenticator authenticator
    ) {
        return new GatewayRateLimitingFilter(
                properties,
                limiter,
                new ObjectMapper(),
                metrics,
                new GatewayRateLimitPolicy(properties, adminProperties(), authenticator)
        );
    }

    private static GatewayAdminProperties adminProperties() {
        return new GatewayAdminProperties("local-dev-admin-token", "", "", "", "", "", "/admin", "", "", "", "", java.util.Set.of(), false, false, false, true);
    }

    private static final class RecordingRateLimiter implements RateLimiter {
        final List<String> keys = new ArrayList<>();

        @Override
        public boolean tryAcquire(String key, int maxRequests, Duration windowDuration) {
            keys.add(key);
            return true;
        }
    }
}
