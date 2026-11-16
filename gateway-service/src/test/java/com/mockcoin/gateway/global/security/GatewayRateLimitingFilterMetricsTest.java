package com.mockcoin.gateway.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.observability.MockcoinMetrics;
import com.mockcoin.shared.security.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class GatewayRateLimitingFilterMetricsTest {

    @Test
    void incrementsRateLimitCounterWhenIpQuotaExceeded() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(0);

        RateLimiter limiter = (key, max, window) -> max > 0;
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/markets");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(response.getContentAsString()).contains("요청 한도를 초과했습니다.");
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("0");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getHeader("X-RateLimit-Reset")).isNotBlank();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        // 거절 사실만으로는 어느 한도를 손봐야 할지 알 수 없으므로 한도 이름이 함께 남아야 한다.
        assertThat(registry.get("mockcoin_rate_limit_exceeded_total")
                .tag("scope", "gateway_ip")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void recordsApiRequestDurationOnPassThrough() throws ServletException, java.io.IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockcoinMetrics metrics = new MockcoinMetrics(registry);
        GatewaySecurityHardeningProperties properties = new GatewaySecurityHardeningProperties();
        properties.setRateLimitEnabled(true);
        properties.setIpRequestsPerMinute(10);

        RateLimiter limiter = (String key, int max, Duration window) -> true;
        GatewayRateLimitingFilter filter = filter(properties, limiter, metrics);

        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/markets");
        ((MockHttpServletRequest) request).setRemoteAddr("10.0.0.2");
        HttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
        };

        filter.doFilter(request, response, chain);

        assertThat(registry.timer("mockcoin_api_request_duration").count()).isEqualTo(1L);
    }

    private static GatewayAdminProperties adminProperties() {
        return new GatewayAdminProperties("", "", "", "", "", "", "/admin", "", "", "", "", java.util.Set.of(), false, false, false, true);
    }

    private static GatewayRateLimitingFilter filter(
            GatewaySecurityHardeningProperties properties,
            RateLimiter limiter,
            MockcoinMetrics metrics
    ) {
        return new GatewayRateLimitingFilter(
                properties,
                limiter,
                new ObjectMapper(),
                metrics,
                new GatewayRateLimitPolicy(properties, adminProperties(), NullTokenAuthenticator.INSTANCE)
        );
    }
}
