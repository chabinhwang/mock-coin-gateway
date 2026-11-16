package com.mockcoin.gateway.global.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

// user/trading/market/ingestor/community 서비스 base URL 라우팅 설정
@ConfigurationProperties(prefix = "mockcoin.routes")
public record ServiceRouteProperties(
        URI userService,
        URI tradingService,
        URI marketService,
        URI ingestorService,
        URI communityService
) {
    // ingestor·community URL이 없으면 localhost 기본 포트(8084, 8086)로 채운다.
    public ServiceRouteProperties {
        ingestorService = ingestorService == null ? URI.create("http://localhost:8084") : ingestorService;
        communityService = communityService == null ? URI.create("http://localhost:8086") : communityService;
    }
}
