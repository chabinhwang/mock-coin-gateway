package com.mockcoin.observability;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer 기반 Prometheus 메트릭 수집기. WS 연결 수, 드롭 메시지, 레이트리밋, API 요청 시간을 추적한다.
 *
 * <p>shared-kernel 모듈은 Spring 의존을 가지지 않으므로 Spring stereotype을 제거했다.
 * 각 서비스 모듈에서 {@code @Bean} 또는 {@code @Component}로 직접 등록한다.
 */
public class MockcoinMetrics {

    static final String RATE_LIMIT_EXCEEDED_METRIC = "mockcoin_rate_limit_exceeded_total";
    static final String SCOPE_TAG = "scope";
    private static final String SCOPE_UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;
    private final AtomicInteger wsConnections;
    private final Counter wsDroppedMessages;
    private final Timer apiRequestDuration;

    // 주어진 MeterRegistry에 mockcoin 관련 메트릭(WS, 레이트리밋, API 요청 시간 등)을 등록한다
    public MockcoinMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.wsConnections = new AtomicInteger(0);
        Gauge.builder("mockcoin_ws_connections", wsConnections, AtomicInteger::get)
                .register(meterRegistry);
        this.wsDroppedMessages = Counter.builder("mockcoin_ws_dropped_messages_total")
                .register(meterRegistry);
        this.apiRequestDuration = Timer.builder("mockcoin_api_request_duration")
                .register(meterRegistry);
    }

    // WebSocket 연결 수를 1 증가시킨다
    public void wsConnected() {
        wsConnections.incrementAndGet();
    }

    // WebSocket 연결 수를 1 감소시킨다(음수가 되지 않도록 보장)
    public void wsDisconnected() {
        wsConnections.updateAndGet(current -> Math.max(0, current - 1));
    }

    // WebSocket 메시지가 드롭된 횟수를 카운트한다
    public void wsMessageDropped() {
        wsDroppedMessages.increment();
    }

    // 레이트리밋 초과 횟수를 어느 한도에 걸렸는지(scope)와 함께 카운트한다
    // scope는 메트릭 라벨이 되므로 호출자가 정한 작은 고정 상수여야 한다(요청에서 파생한 값은 시계열 수를 늘린다).
    public void rateLimitExceeded(String scope) {
        meterRegistry.counter(
                RATE_LIMIT_EXCEEDED_METRIC,
                SCOPE_TAG,
                scope == null || scope.isBlank() ? SCOPE_UNKNOWN : scope
        ).increment();
    }

    // API 요청 처리 시간(나노초)을 기록한다
    public void recordApiDurationNanos(long nanos) {
        if (nanos <= 0) {
            return;
        }
        apiRequestDuration.record(Duration.ofNanos(nanos));
    }
}
