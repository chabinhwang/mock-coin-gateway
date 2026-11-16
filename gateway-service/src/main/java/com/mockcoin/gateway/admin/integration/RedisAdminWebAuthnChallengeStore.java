package com.mockcoin.gateway.admin.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.mockcoin.gateway.admin.config.GatewayAdminRedisProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

// Redis 기반 admin WebAuthn challenge 저장소(pod 간 공유, TTL + GETDEL 원자적 1회 소비)
public class RedisAdminWebAuthnChallengeStore implements AdminWebAuthnChallengeStore {
    private final StringRedisTemplate redis;
    private final GatewayAdminRedisProperties properties;

    // Redis 템플릿과 관리자 Redis 설정을 주입받는다.
    public RedisAdminWebAuthnChallengeStore(
            StringRedisTemplate redis,
            GatewayAdminRedisProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    // 남은 TTL이 0 이하면 저장하지 않고, 아니면 만료 시각까지의 TTL로 Redis에 저장한다.
    @Override
    public void save(Purpose purpose, String challengeId, String requestJson, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redis.opsForValue().set(challengeKey(purpose, challengeId), requestJson, ttl);
    }

    // GETDEL로 challenge를 원자적으로 꺼내 제거하고, 입력이 비었거나 미존재·만료면 empty를 반환한다.
    @Override
    public Optional<String> take(Purpose purpose, String challengeId) {
        if (purpose == null || challengeId == null || challengeId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().getAndDelete(challengeKey(purpose, challengeId)));
    }

    // Redis TTL이 만료를 처리하므로 아무것도 하지 않는다.
    @Override
    public void purgeExpired(Instant now) {
        // 만료는 Redis TTL이 담당한다.
    }

    // prefix + challenge:{목적}:{challengeId} 형식의 Redis 키를 만든다.
    private String challengeKey(Purpose purpose, String challengeId) {
        return properties.redisKeyPrefix()
                + "challenge:"
                + purpose.name().toLowerCase(java.util.Locale.ROOT)
                + ":"
                + challengeId;
    }
}
