package com.mockcoin.gateway.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// admin 세션/잠금/패스키/WebAuthn challenge 저장소의 Redis 백엔드 설정
// 로컬 개발·테스트가 Redis 없이 돌도록 기본 비활성이며, 다인스턴스 배포에서 켜면
// admin 세션·IP 잠금·등록 패스키·진행 중 WebAuthn challenge를 pod 간에 공유한다.
@ConfigurationProperties(prefix = "mockcoin.gateway.admin")
public record GatewayAdminRedisProperties(boolean redisEnabled, String redisKeyPrefix) {
    // key prefix가 비어 있으면 gateway:admin:으로 채우고, 끝이 ':'가 되도록 맞춘다.
    public GatewayAdminRedisProperties {
        if (redisKeyPrefix == null || redisKeyPrefix.isBlank()) {
            redisKeyPrefix = "gateway:admin:";
        }
        if (!redisKeyPrefix.endsWith(":")) {
            redisKeyPrefix = redisKeyPrefix + ":";
        }
    }
}
