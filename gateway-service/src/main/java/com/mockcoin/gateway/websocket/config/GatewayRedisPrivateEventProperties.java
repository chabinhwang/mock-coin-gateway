package com.mockcoin.gateway.websocket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// gateway private 이벤트 Redis Pub/Sub 활성화 및 채널 prefix 설정
@ConfigurationProperties(prefix = "mockcoin.websocket.redis-private-events")
public record GatewayRedisPrivateEventProperties(boolean enabled, String channelPrefix) {
    // channelPrefix가 비어 있으면 기본값 ws:private:로 채운다.
    public GatewayRedisPrivateEventProperties {
        if (channelPrefix == null || channelPrefix.isBlank()) {
            channelPrefix = "ws:private:";
        }
    }
}
