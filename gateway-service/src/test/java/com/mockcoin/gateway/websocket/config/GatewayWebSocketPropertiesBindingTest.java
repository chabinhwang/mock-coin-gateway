package com.mockcoin.gateway.websocket.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class GatewayWebSocketPropertiesBindingTest {

    @Test
    void commaSeparatedAllowedOriginPatternsBindAsList() {
        // application.yml은 GATEWAY_WS_ALLOWED_ORIGIN_PATTERNS 문자열을 그대로 넘기므로 쉼표 구분 목록으로 풀려야 한다.
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "mockcoin.websocket.allowed-origin-patterns",
                "https://*.apps.tossmini.com,https://*.private-apps.tossmini.com,https://api.example.com"
        )));

        GatewayWebSocketProperties properties = binder
                .bind("mockcoin.websocket", GatewayWebSocketProperties.class)
                .get();

        assertThat(properties.allowedOriginPatterns()).containsExactly(
                "https://*.apps.tossmini.com",
                "https://*.private-apps.tossmini.com",
                "https://api.example.com"
        );
    }
}
