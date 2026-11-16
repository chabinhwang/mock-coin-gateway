package com.mockcoin.gateway.global.security;

import com.mockcoin.shared.security.ratelimit.RedisRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// Redis가 있을 때 등록되는 sliding-window rate limiter 빈 — 로직은 공통 RedisRateLimiter.
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class GatewayRedisRateLimiter extends RedisRateLimiter {
    // Redis 템플릿을 주입받아 공통 RedisRateLimiter에 넘긴다.
    public GatewayRedisRateLimiter(StringRedisTemplate redisTemplate) {
        super(redisTemplate);
    }
}
