package com.mockcoin.shared.security.ratelimit;

import java.time.Duration;
import java.time.Instant;

// rate-limit 추상화 (Redis sliding window 또는 in-memory fallback). gateway/user 공통 코어.
public interface RateLimiter {
    // 키 단위로 윈도우 내 요청 허용 여부를 반환한다
    boolean tryAcquire(String key, int maxRequests, Duration windowDuration);

    // 키 단위로 요청 허용 여부와 응답 헤더에 사용할 제한 정보(limit/remaining/reset)를 반환한다
    default Decision check(String key, int maxRequests, Duration windowDuration) {
        boolean allowed = tryAcquire(key, maxRequests, windowDuration);
        return new Decision(
                allowed,
                maxRequests,
                allowed ? Math.max(0, maxRequests - 1) : 0,
                Instant.now().plus(windowDuration)
        );
    }

    // rate-limit 판정 결과(허용 여부와 limit/remaining/reset 헤더 값)
    record Decision(boolean allowed, int limit, int remaining, Instant resetAt) {
    }
}
