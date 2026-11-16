package com.mockcoin.shared.security.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Redis가 없을 때 사용하는 in-process rate limiter fallback.
// 윈도우 정책은 fixed window다 — 첫 요청이 윈도우 종료 시각을 정하고 후속 요청은 이를 연장하지 않는다.
// (Redis 구현의 sliding window와 달리 윈도우 경계에서 최대 2배 burst가 가능하다.
//  단일 인스턴스 폴백 용도라 의도적으로 단순하게 둔다.)
// Spring 빈 등록(@Component/@ConditionalOnMissingBean)은 각 서비스의 서브클래스가 담당한다.
public class InMemoryRateLimiter implements RateLimiter {
    private static final long CLEANUP_INTERVAL_OPERATIONS = 1_024;

    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();

    // 키 단위 카운터를 증가시키고 윈도우 내 요청이 한도를 넘지 않았는지 판단한다
    @Override
    public boolean tryAcquire(String key, int maxRequests, Duration windowDuration) {
        return check(key, maxRequests, windowDuration).allowed();
    }

    // 키 단위 카운터를 증가시키고 응답 헤더에 필요한 remaining/reset 정보를 함께 반환한다
    @Override
    public Decision check(String key, int maxRequests, Duration windowDuration) {
        Instant now = Instant.now();
        maybeCleanup(now);
        WindowState updated = windows.compute(key, (ignored, state) -> {
            if (state == null || now.isAfter(state.windowEnd())) {
                return new WindowState(1, now.plus(windowDuration));
            }
            return new WindowState(state.count() + 1, state.windowEnd());
        });
        boolean allowed = updated.count() <= maxRequests;
        return new Decision(
                allowed,
                maxRequests,
                allowed ? Math.max(0, maxRequests - updated.count()) : 0,
                updated.windowEnd()
        );
    }

    // 1,024번 호출마다 윈도우가 끝난 키를 맵에서 지운다.
    private void maybeCleanup(Instant now) {
        long operationCount = operations.incrementAndGet();
        if (operationCount % CLEANUP_INTERVAL_OPERATIONS != 0) {
            return;
        }
        windows.entrySet().removeIf(entry -> now.isAfter(entry.getValue().windowEnd()));
    }

    // 키별 fixed window 상태(윈도우 내 요청 수, 윈도우 종료 시각)
    private record WindowState(int count, Instant windowEnd) {
    }
}
