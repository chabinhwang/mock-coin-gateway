package com.mockcoin.shared.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 다중 인스턴스에서 rate limit을 원자적으로 집계하는 Redis sliding-window limiter.
 *
 * <p>Lua 스크립트에 넘기는 인자 계약과, Redis가 비정상일 때 fail-open으로 새지 않는지를 고정한다.
 */
@SuppressWarnings("unchecked") // Mockito matcher가 RedisScript<T>를 raw로 받는다
class RedisRateLimiterTest {
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private StringRedisTemplate redisTemplate;
    private RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        limiter = new RedisRateLimiter(redisTemplate);
    }

    @Test
    void 스크립트_결과가_한도_이하이면_허용한다() {
        stubScriptResult(3L);

        RateLimiter.Decision decision = limiter.check("user:1", 5, WINDOW);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.limit()).isEqualTo(5);
        assertThat(decision.remaining()).isEqualTo(2);
    }

    @Test
    void 스크립트_결과가_한도와_같으면_허용하되_remaining은_0이다() {
        stubScriptResult(5L);

        RateLimiter.Decision decision = limiter.check("user:1", 5, WINDOW);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isZero();
    }

    @Test
    void 스크립트_결과가_한도를_넘으면_거부한다() {
        stubScriptResult(6L);

        RateLimiter.Decision decision = limiter.check("user:1", 5, WINDOW);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
    }

    @Test
    void 스크립트_결과가_null이면_fail_closed로_거부한다() {
        // Redis가 예상 밖 응답을 주더라도 통과시키면 rate limit이 무력화된다.
        stubScriptResult(null);

        assertThat(limiter.tryAcquire("user:1", 5, WINDOW)).isFalse();
        assertThat(limiter.check("user:1", 5, WINDOW).remaining()).isZero();
    }

    @Test
    void tryAcquire는_check와_동일한_판정을_따른다() {
        stubScriptResult(1L);
        assertThat(limiter.tryAcquire("user:1", 5, WINDOW)).isTrue();

        stubScriptResult(99L);
        assertThat(limiter.tryAcquire("user:1", 5, WINDOW)).isFalse();
    }

    @Test
    void 키에_rl_접두사를_붙여_다른_용도의_키와_충돌하지_않게_한다() {
        stubScriptResult(1L);

        limiter.check("ip:127.0.0.1", 5, WINDOW);

        assertThat(capturedKeys()).containsExactly("rl:ip:127.0.0.1");
    }

    @Test
    void 스크립트에_윈도우_시작_현재시각_고유멤버_만료초를_순서대로_전달한다() {
        stubScriptResult(1L);
        Instant before = Instant.now();

        limiter.check("user:1", 5, WINDOW);

        Object[] args = capturedArgs();
        assertThat(args).hasSize(4);

        long windowStart = Long.parseLong((String) args[0]);
        long now = Long.parseLong((String) args[1]);
        String member = (String) args[2];
        long expireSeconds = Long.parseLong((String) args[3]);

        assertThat(now - windowStart)
                .as("윈도우 시작은 현재 시각에서 윈도우 길이만큼 뺀 값이어야 한다")
                .isEqualTo(WINDOW.toMillis());
        assertThat(now).isBetween(before.toEpochMilli(), Instant.now().toEpochMilli());
        assertThat(member).startsWith(now + ":");
        assertThat(expireSeconds)
                .as("TTL은 윈도우보다 길어야 잔여 엔트리가 조기 삭제되지 않는다")
                .isEqualTo(WINDOW.toSeconds() + 1);
    }

    @Test
    void 동일_키_연속_호출에도_ZSET_멤버는_매번_달라진다() {
        // 멤버가 겹치면 ZADD가 덮어써서 카운트가 실제보다 적게 잡힌다.
        stubScriptResult(1L);

        limiter.check("user:1", 5, WINDOW);
        limiter.check("user:1", 5, WINDOW);
        limiter.check("user:1", 5, WINDOW);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.times(3))
                .execute(any(RedisScript.class), anyList(), captor.capture());

        List<Object[]> calls = captor.getAllValues();
        assertThat(calls).hasSize(3);
        assertThat(calls.stream().map(args -> (String) args[2]).distinct()).hasSize(3);
    }

    @Test
    void resetAt은_현재시각_기준_윈도우_종료시각이다() {
        stubScriptResult(1L);
        // 구현이 epochMilli로 내림하므로 비교 기준도 ms로 맞춘다.
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        Instant resetAt = limiter.check("user:1", 5, WINDOW).resetAt();

        assertThat(resetAt).isBetween(before.plus(WINDOW), Instant.now().plus(WINDOW));
    }

    @Test
    void Redis_장애는_삼키지_않고_호출자에게_전파한다() {
        // 여기서 조용히 true를 반환하면 Redis 장애 시 rate limit이 통째로 사라진다.
        // 호출 필터가 장애를 인지하고 자체 정책(차단/폴백)을 적용할 수 있어야 한다.
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> limiter.check("user:1", 5, WINDOW))
                .isInstanceOf(RedisConnectionFailureException.class);
        assertThatThrownBy(() -> limiter.tryAcquire("user:1", 5, WINDOW))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    void Redis_타임아웃도_전파한다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> limiter.tryAcquire("user:1", 5, WINDOW))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void 스크립트는_sliding_window_lua를_로드하고_Long을_기대한다() {
        stubScriptResult(1L);

        limiter.check("user:1", 5, WINDOW);

        ArgumentCaptor<RedisScript<Long>> captor = captorForScript();
        org.mockito.Mockito.verify(redisTemplate)
                .execute(captor.capture(), anyList(), any(Object[].class));

        RedisScript<Long> script = captor.getValue();
        assertThat(script.getResultType()).isEqualTo(Long.class);
        assertThat(script.getScriptAsString())
                .contains("ZREMRANGEBYSCORE")
                .contains("ZADD")
                .contains("ZCARD")
                .contains("EXPIRE");
    }

    // ---------------------------------------------------------------- helpers

    private void stubScriptResult(Long result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(result);
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedKeys() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(redisTemplate)
                .execute(any(RedisScript.class), captor.capture(), any(Object[].class));
        return captor.getValue();
    }

    private Object[] capturedArgs() {
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(redisTemplate)
                .execute(any(RedisScript.class), anyList(), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<RedisScript<Long>> captorForScript() {
        return ArgumentCaptor.forClass(RedisScript.class);
    }
}
