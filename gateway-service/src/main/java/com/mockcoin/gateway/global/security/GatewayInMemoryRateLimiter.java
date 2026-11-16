package com.mockcoin.gateway.global.security;

import com.mockcoin.shared.security.ratelimit.InMemoryRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis가 없을 때 등록되는 in-memory rate limiter 빈. 로직은 공통 {@link InMemoryRateLimiter}.
 * Redis가 있으면 {@link GatewayRedisRateLimiter}가 대신 등록되어 정확히 하나만 활성화된다.
 */
@Component
@ConditionalOnMissingBean(StringRedisTemplate.class)
public class GatewayInMemoryRateLimiter extends InMemoryRateLimiter {
}
