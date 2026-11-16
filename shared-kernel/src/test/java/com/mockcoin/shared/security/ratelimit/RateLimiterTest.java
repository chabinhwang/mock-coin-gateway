package com.mockcoin.shared.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * `RateLimiter.check()` 기본 구현 계약.
 *
 * <p>`tryAcquire`만 구현한 커스텀 limiter도 응답 헤더에 쓸 Decision을 얻을 수 있어야 한다.
 */
class RateLimiterTest {
    private static final Duration WINDOW = Duration.ofSeconds(30);

    @Test
    void 기본_check는_tryAcquire_결과를_그대로_따른다() {
        assertThat(new FixedDecisionLimiter(true).check("k", 10, WINDOW).allowed()).isTrue();
        assertThat(new FixedDecisionLimiter(false).check("k", 10, WINDOW).allowed()).isFalse();
    }

    @Test
    void 허용이면_remaining은_한도에서_이번_요청만큼_뺀_값이다() {
        RateLimiter.Decision decision = new FixedDecisionLimiter(true).check("k", 10, WINDOW);

        assertThat(decision.limit()).isEqualTo(10);
        assertThat(decision.remaining()).isEqualTo(9);
    }

    @Test
    void 거부면_remaining은_0이다() {
        RateLimiter.Decision decision = new FixedDecisionLimiter(false).check("k", 10, WINDOW);

        assertThat(decision.limit()).isEqualTo(10);
        assertThat(decision.remaining()).isZero();
    }

    @Test
    void 한도가_0이어도_remaining이_음수가_되지_않는다() {
        assertThat(new FixedDecisionLimiter(true).check("k", 0, WINDOW).remaining()).isZero();
    }

    @Test
    void resetAt은_현재시각에_윈도우를_더한_값이다() {
        Instant before = Instant.now();

        Instant resetAt = new FixedDecisionLimiter(true).check("k", 10, WINDOW).resetAt();

        assertThat(resetAt).isBetween(before.plus(WINDOW), Instant.now().plus(WINDOW));
    }

    @Test
    void 기본_check는_tryAcquire를_정확히_한_번만_호출한다() {
        // 두 번 호출하면 카운터가 이중 증가해 실제 한도의 절반만 허용된다.
        FixedDecisionLimiter limiter = new FixedDecisionLimiter(true);

        limiter.check("k", 10, WINDOW);

        assertThat(limiter.calls).containsExactly("k|10|PT30S");
    }

    private static final class FixedDecisionLimiter implements RateLimiter {
        private final boolean allowed;
        private final List<String> calls = new ArrayList<>();

        private FixedDecisionLimiter(boolean allowed) {
            this.allowed = allowed;
        }

        @Override
        public boolean tryAcquire(String key, int maxRequests, Duration windowDuration) {
            calls.add(key + "|" + maxRequests + "|" + windowDuration);
            return allowed;
        }
    }
}
