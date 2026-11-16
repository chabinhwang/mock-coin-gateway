package com.mockcoin.gateway.websocket.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class WebSocketConnectionLimiterTest {
    @Test
    void purgeRemovesOnlyExpiredAttemptWindows() {
        WebSocketConnectionLimiter limiter = new WebSocketConnectionLimiter();
        Instant beforeAttempts = Instant.now();
        assertThat(limiter.tryAcquireConnection("10.0.0.1", 30, 10, 10)).isTrue();
        assertThat(limiter.tryAcquireConnection("10.0.0.2", 30, 10, 10)).isTrue();
        limiter.releaseConnection("10.0.0.1", null);
        limiter.releaseConnection("10.0.0.2", null);

        assertThat(limiter.purgeExpiredAttempts(beforeAttempts.plusSeconds(59))).isZero();
        assertThat(limiter.purgeExpiredAttempts(beforeAttempts.plusSeconds(61))).isEqualTo(2);
        assertThat(limiter.purgeExpiredAttempts(beforeAttempts.plusSeconds(62))).isZero();
    }
}
