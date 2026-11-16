package com.mockcoin.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MockcoinMetricsTest {

    private MeterRegistry registry;
    private MockcoinMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MockcoinMetrics(registry);
    }

    @Test
    void wsConnectionGaugeTracksConnects() {
        metrics.wsConnected();
        metrics.wsConnected();
        metrics.wsDisconnected();

        assertThat(registry.get("mockcoin_ws_connections").gauge().value()).isEqualTo(1.0d);
    }

    @Test
    void wsDisconnectDoesNotGoBelowZero() {
        metrics.wsDisconnected();
        metrics.wsDisconnected();

        assertThat(registry.get("mockcoin_ws_connections").gauge().value()).isEqualTo(0.0d);
    }

    @Test
    void countersIncrementOnEachCall() {
        metrics.wsMessageDropped();
        metrics.rateLimitExceeded("user_auth");

        assertThat(registry.get("mockcoin_ws_dropped_messages_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("mockcoin_rate_limit_exceeded_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void rateLimitRejectionsAreSeparableByTheLimitThatRejectedThem() {
        metrics.rateLimitExceeded("user_auth");
        metrics.rateLimitExceeded("user_auth");
        metrics.rateLimitExceeded("user_ip");

        assertThat(rateLimitCount("user_auth")).isEqualTo(2.0d);
        assertThat(rateLimitCount("user_ip")).isEqualTo(1.0d);
    }

    @Test
    void missingScopeStillCountsUnderAKnownLabelInsteadOfFailing() {
        // 메트릭 기록이 요청 처리를 깨뜨려서는 안 된다.
        metrics.rateLimitExceeded(null);
        metrics.rateLimitExceeded("  ");

        assertThat(rateLimitCount("unknown")).isEqualTo(2.0d);
    }

    private double rateLimitCount(String scope) {
        return registry.get("mockcoin_rate_limit_exceeded_total").tag("scope", scope).counter().count();
    }

    @Test
    void apiTimerRecordsPositiveDurations() {
        metrics.recordApiDurationNanos(1_000_000L); // 1ms
        metrics.recordApiDurationNanos(2_000_000L); // 2ms
        metrics.recordApiDurationNanos(0L); // ignored
        metrics.recordApiDurationNanos(-5L); // ignored

        var timer = registry.get("mockcoin_api_request_duration").timer();
        assertThat(timer.count()).isEqualTo(2L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(3.0d);
    }
}
