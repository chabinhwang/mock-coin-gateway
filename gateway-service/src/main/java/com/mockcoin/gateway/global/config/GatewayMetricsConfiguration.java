package com.mockcoin.gateway.global.config;

import com.mockcoin.observability.MockcoinMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// MeterRegistry 및 MockcoinMetrics 빈을 노출하는 메트릭 설정
@Configuration
public class GatewayMetricsConfiguration {

    // MeterRegistry가 없을 때 기본 SimpleMeterRegistry를 제공한다
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    // 공용 mock-coin 비즈니스 메트릭 컴포넌트를 등록한다
    @Bean
    MockcoinMetrics mockcoinMetrics(MeterRegistry meterRegistry) {
        return new MockcoinMetrics(meterRegistry);
    }
}
