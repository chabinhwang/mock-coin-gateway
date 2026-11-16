package com.mockcoin.gateway.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GatewayRateLimitPolicyTest {

    @Test
    void appliesOnlyToNonOptionsPublicAndConfiguredAdminApis() {
        GatewayRateLimitPolicy policy = policy(new GatewaySecurityHardeningProperties(), NullTokenAuthenticator.INSTANCE);

        assertThat(policy.appliesTo(request("GET", "/api/v1/markets"))).isTrue();
        assertThat(policy.appliesTo(request("GET", "/admin/api/auth/status"))).isTrue();
        assertThat(policy.appliesTo(request("OPTIONS", "/api/v1/markets"))).isFalse();
        assertThat(policy.appliesTo(request("GET", "/actuator/health"))).isFalse();
    }

    @Test
    void rulesReuseResolvedUserAndTrustedForwardedClientIp() {
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setTrustedProxyCidrs(List.of("10.0.0.0/8"));
        properties.setIpRequestsPerMinute(101);
        properties.setAuthenticatedRequestsPerMinute(202);
        properties.setOrderRequestsPerMinute(303);
        StubTokenAuthenticator authenticator = new StubTokenAuthenticator();
        authenticator.put("Bearer request-token", 777L);
        GatewayRateLimitPolicy policy = policy(properties, authenticator);
        MockHttpServletRequest request = request("POST", "/api/v1/orders");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "203.0.113.5");
        request.addHeader("Authorization", "Bearer request-token");

        List<GatewayRateLimitPolicy.Rule> rules = policy.rulesFor(request);

        assertThat(rules).containsExactly(
                new GatewayRateLimitPolicy.Rule("gateway_ip", "global:ip:203.0.113.5", 101, Duration.ofMinutes(1)),
                new GatewayRateLimitPolicy.Rule("gateway_user", "user:all:uid:777", 202, Duration.ofMinutes(1)),
                new GatewayRateLimitPolicy.Rule("gateway_order", "order:uid:777", 303, Duration.ofMinutes(1))
        );
        assertThat(request.getAttribute(GatewayTokenAuthenticator.AUTHENTICATED_USER_ID_REQUEST_ATTRIBUTE))
                .isEqualTo(777L);
    }

    @Test
    void rulesIgnoreForwardedClientIpFromUntrustedPeer() {
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setTrustedProxyCidrs(List.of("10.0.0.0/8"));
        GatewayRateLimitPolicy policy = policy(properties, NullTokenAuthenticator.INSTANCE);
        MockHttpServletRequest request = request("GET", "/api/v1/markets");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "203.0.113.5");

        assertThat(policy.rulesFor(request))
                .extracting(GatewayRateLimitPolicy.Rule::key)
                .containsExactly("global:ip:192.0.2.10", "market:read:ip:192.0.2.10");
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static GatewayRateLimitPolicy policy(
            GatewaySecurityHardeningProperties properties,
            GatewayTokenAuthenticator authenticator
    ) {
        return new GatewayRateLimitPolicy(properties, adminProperties(), authenticator);
    }

    private static GatewayAdminProperties adminProperties() {
        return new GatewayAdminProperties(
                "local-dev-admin-token", "", "", "", "", "", "/admin", "", "", "", "",
                java.util.Set.of(), false, false, false, true
        );
    }
}
