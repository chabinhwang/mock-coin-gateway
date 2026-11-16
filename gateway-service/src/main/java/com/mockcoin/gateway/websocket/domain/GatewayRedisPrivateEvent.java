package com.mockcoin.gateway.websocket.domain;

import java.time.Instant;
import java.util.Map;

// Redis Pub/Sub 채널로 직렬화되는 사용자별 private 이벤트 페이로드
public record GatewayRedisPrivateEvent(Long userId, String type, Instant ts, Map<String, Object> data) {
}
