package com.mockcoin.shared.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Redis가 없을 때 gateway/user-service가 사용하는 in-process rate limiter fallback.
 *
 * <p>단일 인스턴스 한정이므로 과다 허용(over-admission)이 곧 서비스 보호 실패다.
 * 특히 동시 요청에서 한도를 정확히 지키는지를 고정한다.
 */
class InMemoryRateLimiterTest {
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();

    @Test
    void 한도까지_허용하고_초과분은_거부한다() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(limiter.tryAcquire("user:1", 3, WINDOW))
                    .as("%d번째 요청은 허용돼야 한다", attempt)
                    .isTrue();
        }

        assertThat(limiter.tryAcquire("user:1", 3, WINDOW)).isFalse();
        assertThat(limiter.tryAcquire("user:1", 3, WINDOW)).isFalse();
    }

    @Test
    void remaining은_한도에서_사용량만큼_줄어든다() {
        assertThat(limiter.check("user:1", 3, WINDOW).remaining()).isEqualTo(2);
        assertThat(limiter.check("user:1", 3, WINDOW).remaining()).isEqualTo(1);
        assertThat(limiter.check("user:1", 3, WINDOW).remaining()).isZero();
    }

    @Test
    void 거부된_요청의_remaining은_0이고_limit은_유지된다() {
        limiter.check("user:1", 1, WINDOW);

        RateLimiter.Decision denied = limiter.check("user:1", 1, WINDOW);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.limit()).isEqualTo(1);
    }

    @Test
    void 키가_다르면_카운터가_서로_독립이다() {
        assertThat(limiter.tryAcquire("user:1", 1, WINDOW)).isTrue();

        // user:1은 소진됐지만 user:2는 영향받지 않아야 한다.
        assertThat(limiter.tryAcquire("user:1", 1, WINDOW)).isFalse();
        assertThat(limiter.tryAcquire("user:2", 1, WINDOW)).isTrue();
    }

    @Test
    void 윈도우가_끝나면_카운터가_초기화된다() throws Exception {
        Duration shortWindow = Duration.ofMillis(60);

        assertThat(limiter.tryAcquire("user:1", 1, shortWindow)).isTrue();
        assertThat(limiter.tryAcquire("user:1", 1, shortWindow)).isFalse();

        Thread.sleep(shortWindow.toMillis() + 40);

        assertThat(limiter.tryAcquire("user:1", 1, shortWindow))
                .as("윈도우 만료 후에는 다시 허용돼야 한다")
                .isTrue();
    }

    @Test
    void resetAt은_첫_요청_기준_고정_윈도우_종료시각이다() throws Exception {
        // 구현은 sliding window가 아니라 fixed window다. 첫 요청이 윈도우 종료 시각을 정하고,
        // 같은 윈도우 안의 후속 요청은 종료 시각을 밀지 않는다.
        Instant before = Instant.now();
        Instant firstResetAt = limiter.check("user:1", 5, WINDOW).resetAt();
        assertThat(firstResetAt).isBetween(before.plus(WINDOW).minusSeconds(2), Instant.now().plus(WINDOW));

        Thread.sleep(30);

        assertThat(limiter.check("user:1", 5, WINDOW).resetAt())
                .as("후속 요청이 윈도우를 연장하면 안 된다")
                .isEqualTo(firstResetAt);
    }

    @Test
    void 동시_요청에서도_한도를_초과해_허용하지_않는다() throws Exception {
        int threads = 16;
        int attemptsPerThread = 40;
        int maxRequests = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < attemptsPerThread; i++) {
                            if (limiter.tryAcquire("hot-key", maxRequests, WINDOW)) {
                                allowed.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            // 총 640회 시도 중 정확히 maxRequests만 통과해야 한다.
            assertThat(allowed.get()).isEqualTo(maxRequests);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 서로_다른_키를_대량으로_써도_카운팅이_정확하다() {
        // 내부 cleanup은 1024 연산마다 동작한다. cleanup 경로를 밟은 뒤에도
        // 살아있는 윈도우의 카운터가 지워지지 않아야 한다.
        for (int i = 0; i < 3_000; i++) {
            assertThat(limiter.tryAcquire("bulk:" + i, 1, WINDOW)).isTrue();
        }

        // 위에서 각 키가 이미 한도를 소진했으므로 두 번째 요청은 모두 거부여야 한다.
        for (int i = 0; i < 3_000; i++) {
            assertThat(limiter.tryAcquire("bulk:" + i, 1, WINDOW)).isFalse();
        }
    }

    @Test
    void 한도가_0이면_모든_요청을_거부한다() {
        assertThat(limiter.tryAcquire("user:1", 0, WINDOW)).isFalse();
        assertThat(limiter.check("user:1", 0, WINDOW).remaining()).isZero();
    }
}
