package com.mockcoin.gateway.admin.integration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.mockcoin.gateway.admin.config.GatewayAdminRedisProperties;
import com.mockcoin.gateway.admin.integration.AdminLockoutStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// Redis 기반 admin 로그인 잠금 상태 저장소(TTL 기반 만료)
// <prefix>lockout:<ip> Hash에 failures와 선택적 lockedUntil을 두고, 실패 카운터는 24시간 롤링 TTL로 방치 시 사라지며
// 잠금이 걸리면 TTL을 잠금 기간으로 맞춰 Redis가 pod 간 만료를 강제한다.
public class RedisAdminLockoutStore implements AdminLockoutStore {
    private static final Logger log = LoggerFactory.getLogger(RedisAdminLockoutStore.class);
    private static final String FIELD_FAILURES = "failures";
    private static final String FIELD_LOCKED_UNTIL = "lockedUntil";
    private static final long FAILURE_RETENTION_SECONDS = 24L * 60 * 60;

    private final StringRedisTemplate redis;
    private final GatewayAdminRedisProperties properties;

    // Redis 템플릿과 관리자 Redis 설정을 주입받는다.
    public RedisAdminLockoutStore(StringRedisTemplate redis, GatewayAdminRedisProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    // 실패 횟수를 1 올리고, 임계치에 닿으면 잠금 종료 시각을 기록해 TTL을 잠금 기간으로, 아니면 24시간으로 맞춘다.
    @Override
    public LockoutEntry recordFailure(String ip, int maxAttempts, long lockoutDurationSeconds) {
        String key = lockoutKey(ip);
        Long failuresLong = redis.opsForHash().increment(key, FIELD_FAILURES, 1L);
        int failures = failuresLong == null ? 1 : failuresLong.intValue();
        Instant lockedUntil = null;
        if (failures >= maxAttempts) {
            lockedUntil = Instant.now().plusSeconds(lockoutDurationSeconds);
            redis.opsForHash().put(key, FIELD_LOCKED_UNTIL, Long.toString(lockedUntil.toEpochMilli()));
            redis.expire(key, lockoutDurationSeconds, TimeUnit.SECONDS);
        } else {
            // 부분 실패 상태가 영원히 남지 않도록 실패 카운터의 TTL을 다시 늘린다.
            redis.expire(key, FAILURE_RETENTION_SECONDS, TimeUnit.SECONDS);
        }
        return new LockoutEntry(failures, lockedUntil);
    }

    // 잠금 상태를 읽고, 미존재면 empty, 값이 깨졌거나 잠금이 만료됐으면 키를 지운 뒤 empty를 반환한다.
    @Override
    public Optional<LockoutEntry> getLockout(String ip) {
        String key = lockoutKey(ip);
        Object failuresRaw = redis.opsForHash().get(key, FIELD_FAILURES);
        Object lockedUntilRaw = redis.opsForHash().get(key, FIELD_LOCKED_UNTIL);
        if (failuresRaw == null && lockedUntilRaw == null) {
            return Optional.empty();
        }
        int failures;
        Instant lockedUntil = null;
        try {
            failures = failuresRaw == null ? 0 : Integer.parseInt(failuresRaw.toString());
            if (lockedUntilRaw != null) {
                lockedUntil = Instant.ofEpochMilli(Long.parseLong(lockedUntilRaw.toString()));
            }
        } catch (RuntimeException exception) {
            log.warn("Invalid admin lockout payload at key={}, dropping", key, exception);
            redis.delete(key);
            return Optional.empty();
        }
        if (lockedUntil != null && lockedUntil.isBefore(Instant.now())) {
            redis.delete(key);
            return Optional.empty();
        }
        return Optional.of(new LockoutEntry(failures, lockedUntil));
    }

    // ip가 null이 아니면 해당 IP의 잠금 키를 삭제한다.
    @Override
    public void clear(String ip) {
        if (ip != null) {
            redis.delete(lockoutKey(ip));
        }
    }

    private String lockoutKey(String ip) {
        return properties.redisKeyPrefix() + "lockout:" + ip;
    }
}
