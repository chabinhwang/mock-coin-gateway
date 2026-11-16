package com.mockcoin.gateway.websocket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// gateway public market 이벤트 Redis Pub/Sub 활성화 및 채널 prefix 설정
@ConfigurationProperties(prefix = "mockcoin.websocket.redis-market-events")
public record GatewayRedisMarketEventProperties(boolean enabled, String channelPrefix) {
    // channelPrefix가 비어 있으면 기본값 md:broadcast:로 채운다.
    public GatewayRedisMarketEventProperties {
        if (channelPrefix == null || channelPrefix.isBlank()) {
            channelPrefix = "md:broadcast:";
        }
    }
}
