package com.mockcoin.gateway.admin.integration;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.mockcoin.gateway.admin.config.GatewayAdminRedisProperties;
import com.mockcoin.gateway.admin.integration.AdminSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// Redis 기반 admin 세션 토큰 저장소(TTL 기반 만료 + 등록용 세션 인덱스 Set)
// 세션은 <prefix>session:<token> Hash(purpose, expiresAt)에 절대 만료와 같은 TTL로 두어 Redis TTL이 pod 간 만료를 담당하고,
// PASSKEY_REGISTRATION 토큰은 인덱스 Set(<prefix>sessions:registration)으로 추적해 revokeOtherRegistrationSessions가 키 공간을 스캔하지 않는다.
public class RedisAdminSessionStore implements AdminSessionStore {
    private static final Logger log = LoggerFactory.getLogger(RedisAdminSessionStore.class);
    private static final String FIELD_PURPOSE = "purpose";
    private static final String FIELD_EXPIRES_AT = "expiresAt";
    private final StringRedisTemplate redis;
    private final GatewayAdminRedisProperties properties;

    // Redis 템플릿과 관리자 Redis 설정을 주입받는다.
    public RedisAdminSessionStore(StringRedisTemplate redis, GatewayAdminRedisProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    // 세션을 Hash로 저장하고 만료까지 TTL(최소 1초)을 건 뒤, 패스키 등록용이면 등록 인덱스 Set에 넣고 아니면 뺀다.
    @Override
    public void save(String sessionId, Purpose purpose, Instant expiresAt) {
        String key = sessionKey(sessionId);
        long ttlSeconds = Math.max(1, Duration.between(Instant.now(), expiresAt).getSeconds());
        redis.opsForHash().put(key, FIELD_PURPOSE, purpose.name());
        redis.opsForHash().put(key, FIELD_EXPIRES_AT, Long.toString(expiresAt.toEpochMilli()));
        redis.expire(key, ttlSeconds, TimeUnit.SECONDS);
        if (purpose == Purpose.PASSKEY_REGISTRATION) {
            redis.opsForSet().add(registrationIndexKey(), sessionId);
            // 동시에 열린 등록 세션도 보이도록 인덱스 항목은 개별 세션보다 오래 살아야 하므로 TTL 없이 두고,
            // 정리는 revokeOtherRegistrationSessions가 맡는다.
        } else {
            redis.opsForSet().remove(registrationIndexKey(), sessionId);
        }
    }

    // 세션을 조회하고, 미존재면 empty, 값이 깨졌거나 만료됐으면 키를 지운 뒤 empty를 반환한다.
    @Override
    public Optional<SessionEntry> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String key = sessionKey(sessionId);
        Object purposeRaw = redis.opsForHash().get(key, FIELD_PURPOSE);
        Object expiresRaw = redis.opsForHash().get(key, FIELD_EXPIRES_AT);
        if (purposeRaw == null || expiresRaw == null) {
            return Optional.empty();
        }
        Instant expiresAt;
        Purpose purpose;
        try {
            expiresAt = Instant.ofEpochMilli(Long.parseLong(expiresRaw.toString()));
            purpose = Purpose.valueOf(purposeRaw.toString());
        } catch (RuntimeException exception) {
            log.warn("Invalid admin session payload at key={}, dropping", key, exception);
            redis.delete(key);
            return Optional.empty();
        }
        if (expiresAt.isBefore(Instant.now())) {
            redis.delete(key);
            return Optional.empty();
        }
        return Optional.of(new SessionEntry(purpose, expiresAt));
    }

    // 세션 키와 등록 인덱스 Set의 항목을 함께 제거한다.
    @Override
    public void revoke(String sessionId) {
        if (sessionId == null) {
            return;
        }
        redis.delete(sessionKey(sessionId));
        redis.opsForSet().remove(registrationIndexKey(), sessionId);
    }

    // 등록 인덱스 Set을 순회해 keepSessionId를 제외한 등록용 세션 키와 인덱스 항목을 제거한다.
    @Override
    public void revokeOtherRegistrationSessions(String keepSessionId) {
        Set<String> registrationSessions = redis.opsForSet().members(registrationIndexKey());
        if (registrationSessions == null || registrationSessions.isEmpty()) {
            return;
        }
        for (String sessionId : registrationSessions) {
            if (sessionId.equals(keepSessionId)) {
                continue;
            }
            redis.delete(sessionKey(sessionId));
            redis.opsForSet().remove(registrationIndexKey(), sessionId);
        }
    }

    private String sessionKey(String sessionId) {
        return properties.redisKeyPrefix() + "session:" + sessionId;
    }

    private String registrationIndexKey() {
        return properties.redisKeyPrefix() + "sessions:registration";
    }
}
