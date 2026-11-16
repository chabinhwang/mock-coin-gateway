package com.mockcoin.gateway.global.config;

import com.mockcoin.gateway.websocket.config.GatewayWebSocketProperties;
import com.mockcoin.shared.security.InternalTokenAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

// gateway internal event endpoint 공유 토큰 검증 Filter 등록
@Configuration
public class GatewayInternalFilterConfiguration {
    private static final String INTERNAL_EVENT_TOKEN_HEADER = "X-Mockcoin-Internal-Event-Token";

    // internal event 경로(/internal/gateway/events/*, public ws)에 내부 토큰 검증 필터를 최우선 순서로 등록한다.
    @Bean
    FilterRegistrationBean<InternalTokenAuthenticationFilter> gatewayInternalEventTokenFilter(GatewayWebSocketProperties properties) {
        FilterRegistrationBean<InternalTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(
                new InternalTokenAuthenticationFilter(INTERNAL_EVENT_TOKEN_HEADER, properties::internalEventToken, "internal event token is not configured")
        );
        registration.setName("gatewayInternalEventTokenFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/internal/gateway/events/*");
        registration.addUrlPatterns("/internal/gateway/events/public/ws");
        return registration;
    }
}
