package com.mockcoin.gateway.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.mockcoin.gateway.admin.config.GatewayAdminRedisProperties;
import com.mockcoin.gateway.admin.integration.AdminLockoutStore.LockoutEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 어드민 로그인 잠금 저장소의 Redis 구현.
 *
 * <p>이 로직이 느슨해지면 어드민 콘솔이 브루트포스에 노출된다. 인메모리 구현만 테스트돼 있고
 * 운영 gateway가 쓰는 Redis 구현은 검증 공백이었다.
 *
 * <p>핵심 계약: 실패 횟수가 {@code maxAttempts}에 <b>도달</b>하면 잠기고(초과가 아니라 도달),
 * 잠금 만료 후에는 카운터까지 함께 정리되어야 한다.
 */
class RedisAdminLockoutStoreTest {
    private static final String PREFIX = "gateway:admin:";
    private static final String IP = "203.0.113.7";
    private static final String KEY = PREFIX + "lockout:" + IP;
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_SECONDS = 900L;
    private static final long FAILURE_RETENTION_SECONDS = 24L * 60 * 60;

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashes;
    private RedisAdminLockoutStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        store = new RedisAdminLockoutStore(redis, new GatewayAdminRedisProperties(true, PREFIX));
    }

    // ---------------------------------------------------------------- recordFailure

    @ParameterizedTest(name = "실패 {0}회면 아직 잠기지 않는다")
    @CsvSource({"1", "2", "3", "4"})
    void 한도_미만의_실패는_잠그지_않고_카운터_TTL만_연장한다(long failures) {
        when(hashes.increment(KEY, "failures", 1L)).thenReturn(failures);

        LockoutEntry entry = store.recordFailure(IP, MAX_ATTEMPTS, LOCKOUT_SECONDS);

        assertThat(entry.failures()).isEqualTo((int) failures);
        assertThat(entry.lockedUntil()).isNull();
        verify(hashes, never()).put(eq(KEY), eq("lockedUntil"), anyString());
        // 버려진 카운터가 영구히 남지 않도록 rolling TTL을 건다.
        verify(redis).expire(KEY, FAILURE_RETENTION_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void 실패가_한도에_도달하면_잠근다() {
        // "초과(>)"가 아니라 "도달(>=)"이어야 한다. 여기가 어긋나면 시도 1회가 공짜로 늘어난다.
        when(hashes.increment(KEY, "failures", 1L)).thenReturn(5L);
        Instant before = Instant.now();

        LockoutEntry entry = store.recordFailure(IP, MAX_ATTEMPTS, LOCKOUT_SECONDS);

        assertThat(entry.failures()).isEqualTo(MAX_ATTEMPTS);
        assertThat(entry.lockedUntil())
                .isNotNull()
                .isBetween(before.plusSeconds(LOCKOUT_SECONDS - 5), Instant.now().plusSeconds(LOCKOUT_SECONDS));
    }

    @Test
    void 잠금시_lockedUntil을_저장하고_TTL을_잠금창으로_설정한다() {
        when(hashes.increment(KEY, "failures", 1L)).thenReturn(7L);

        store.recordFailure(IP, MAX_ATTEMPTS, LOCKOUT_SECONDS);

        ArgumentCaptor<String> lockedUntil = ArgumentCaptor.forClass(String.class);
        verify(hashes).put(eq(KEY), eq("lockedUntil"), lockedUntil.capture());
        assertThat(Long.parseLong(lockedUntil.getValue()))
                .isGreaterThan(Instant.now().toEpochMilli());
        verify(redis).expire(KEY, LOCKOUT_SECONDS, TimeUnit.SECONDS);
        verify(redis, never()).expire(KEY, FAILURE_RETENTION_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void increment가_null을_주면_1회로_간주한다() {
        // Redis 응답이 비정상이어도 카운터가 0/음수로 무너지면 안 된다.
        when(hashes.increment(KEY, "failures", 1L)).thenReturn(null);

        assertThat(store.recordFailure(IP, MAX_ATTEMPTS, LOCKOUT_SECONDS).failures()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- getLockout

    @Test
    void 기록이_없으면_비어있다() {
        when(hashes.get(KEY, "failures")).thenReturn(null);
        when(hashes.get(KEY, "lockedUntil")).thenReturn(null);

        assertThat(store.getLockout(IP)).isEmpty();
        verify(redis, never()).delete(anyString());
    }

    @Test
    void 잠기지_않은_실패_카운터를_그대로_반환한다() {
        when(hashes.get(KEY, "failures")).thenReturn("3");
        when(hashes.get(KEY, "lockedUntil")).thenReturn(null);

        Optional<LockoutEntry> entry = store.getLockout(IP);

        assertThat(entry).isPresent();
        assertThat(entry.orElseThrow().failures()).isEqualTo(3);
        assertThat(entry.orElseThrow().lockedUntil()).isNull();
    }

    @Test
    void 잠금이_유효하면_lockedUntil을_반환한다() {
        Instant lockedUntil = Instant.now().plusSeconds(600);
        when(hashes.get(KEY, "failures")).thenReturn("5");
        when(hashes.get(KEY, "lockedUntil")).thenReturn(Long.toString(lockedUntil.toEpochMilli()));

        Optional<LockoutEntry> entry = store.getLockout(IP);

        assertThat(entry).isPresent();
        assertThat(entry.orElseThrow().lockedUntil()).isEqualTo(lockedUntil.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void 만료된_잠금은_키를_지우고_비어있다() {
        // 잠금이 풀리면 실패 카운터도 같이 사라져야 다음 시도가 처음부터 시작된다.
        when(hashes.get(KEY, "failures")).thenReturn("5");
        when(hashes.get(KEY, "lockedUntil")).thenReturn(Long.toString(Instant.now().minusSeconds(1).toEpochMilli()));

        assertThat(store.getLockout(IP)).isEmpty();

        verify(redis).delete(KEY);
    }

    @Test
    void lockedUntil만_있고_failures가_없어도_해석한다() {
        Instant lockedUntil = Instant.now().plusSeconds(600);
        when(hashes.get(KEY, "failures")).thenReturn(null);
        when(hashes.get(KEY, "lockedUntil")).thenReturn(Long.toString(lockedUntil.toEpochMilli()));

        Optional<LockoutEntry> entry = store.getLockout(IP);

        assertThat(entry).isPresent();
        assertThat(entry.orElseThrow().failures()).isZero();
    }

    @ParameterizedTest(name = "손상된 payload failures={0} lockedUntil={1}")
    @CsvSource({
            "not-a-number, 1777075500000",
            "3,            not-a-number",
    })
    void 손상된_payload는_키를_지우고_비어있다(String failures, String lockedUntil) {
        when(hashes.get(KEY, "failures")).thenReturn(failures);
        when(hashes.get(KEY, "lockedUntil")).thenReturn(lockedUntil);

        assertThat(store.getLockout(IP)).isEmpty();

        verify(redis).delete(KEY);
    }

    // ---------------------------------------------------------------- clear

    @Test
    void 로그인_성공시_잠금_상태를_지운다() {
        store.clear(IP);

        verify(redis).delete(KEY);
    }

    @Test
    void null_IP는_아무것도_지우지_않는다() {
        store.clear(null);

        verify(redis, never()).delete(anyString());
    }

    @Test
    void IP별로_키가_분리되어_서로_영향을_주지_않는다() {
        store.clear("198.51.100.9");

        verify(redis).delete(PREFIX + "lockout:198.51.100.9");
        verify(redis, never()).delete(KEY);
    }
}
