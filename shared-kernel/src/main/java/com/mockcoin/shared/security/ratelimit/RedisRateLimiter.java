package com.mockcoin.shared.security.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

// Redis Sorted Set 기반 sliding-window rate limiter (Lua 스크립트로 원자 실행).
// Spring 빈 등록(@Component/@ConditionalOnBean)은 각 서비스의 서브클래스가 담당한다.
public class RedisRateLimiter implements RateLimiter {
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> slidingWindowScript;

    // Redis 템플릿을 받고 클래스패스의 sliding-window Lua 스크립트를 Long 결과 타입으로 로드한다.
    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = new DefaultRedisScript<>();
        this.slidingWindowScript.setLocation(new ClassPathResource("scripts/sliding-window-rate-limit.lua"));
        this.slidingWindowScript.setResultType(Long.class);
    }

    // Redis sliding-window Lua 스크립트를 실행해 윈도우 카운트가 한도 이하인지 판정한다
    @Override
    public boolean tryAcquire(String key, int maxRequests, Duration windowDuration) {
        return check(key, maxRequests, windowDuration).allowed();
    }

    // Redis sliding-window Lua 스크립트를 실행하고 응답 헤더에 필요한 remaining/reset 정보를 함께 반환한다
    @Override
    public Decision check(String key, int maxRequests, Duration windowDuration) {
        long now = Instant.now().toEpochMilli();
        long windowStart = now - windowDuration.toMillis();
        String redisKey = "rl:" + key;

        Long count = redisTemplate.execute(
                slidingWindowScript,
                List.of(redisKey),
                String.valueOf(windowStart),
                String.valueOf(now),
                now + ":" + UUID.randomUUID(),
                String.valueOf(windowDuration.toSeconds() + 1)
        );

        long currentCount = count == null ? maxRequests + 1L : count;
        boolean allowed = currentCount <= maxRequests;
        return new Decision(
                allowed,
                maxRequests,
                allowed ? Math.max(0, (int) (maxRequests - currentCount)) : 0,
                Instant.ofEpochMilli(now).plus(windowDuration)
        );
    }
}
