package com.mockcoin.gateway.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// gateway → downstream HTTP 호출 connect/read timeout 설정
@ConfigurationProperties(prefix = "mockcoin.http")
public record GatewayHttpProperties(Duration connectTimeout, Duration readTimeout) {
    // timeout이 없으면 connect 1초, read 8초를 기본값으로 쓴다.
    public GatewayHttpProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(8) : readTimeout;
    }
}
