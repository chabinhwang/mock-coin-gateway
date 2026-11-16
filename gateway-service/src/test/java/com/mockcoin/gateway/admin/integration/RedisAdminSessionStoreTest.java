package com.mockcoin.gateway.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.mockcoin.gateway.admin.config.GatewayAdminRedisProperties;
import com.mockcoin.gateway.admin.integration.AdminSessionStore.Purpose;
import com.mockcoin.gateway.admin.integration.AdminSessionStore.SessionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 어드민 콘솔 세션 저장소의 Redis 구현.
 *
 * <p>인메모리 구현({@link InMemoryAdminSessionStore})은 단일 인스턴스 전용이고, 운영 gateway는
 * 이 Redis 구현을 쓴다. 세션 만료가 새면 로그아웃된 어드민 토큰이 계속 통하므로 만료·폐기
 * 경로를 특히 촘촘히 고정한다.
 */
class RedisAdminSessionStoreTest {
    private static final String PREFIX = "gateway:admin:";
    private static final String REGISTRATION_INDEX = PREFIX + "sessions:registration";
    private static final String SESSION_ID = "session-token-1";
    private static final String SESSION_KEY = PREFIX + "session:" + SESSION_ID;

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashes;
    private SetOperations<String, String> sets;
    private RedisAdminSessionStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashes = mock(HashOperations.class);
        sets = mock(SetOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForSet()).thenReturn(sets);
        store = new RedisAdminSessionStore(redis, new GatewayAdminRedisProperties(true, PREFIX));
    }

    // ---------------------------------------------------------------- save

    @Test
    void 세션을_purpose와_만료시각_필드로_저장하고_TTL을_건다() {
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES);

        store.save(SESSION_ID, Purpose.ADMIN, expiresAt);

        verify(hashes).put(SESSION_KEY, "purpose", "ADMIN");
        verify(hashes).put(SESSION_KEY, "expiresAt", Long.toString(expiresAt.toEpochMilli()));

        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(redis).expire(eq(SESSION_KEY), ttl.capture(), eq(TimeUnit.SECONDS));
        assertThat(ttl.getValue()).isBetween(29L * 60, 30L * 60);
    }

    @Test
    void 이미_만료된_시각으로_저장해도_TTL이_최소_1초는_된다() {
        // TTL이 0/음수면 Redis가 키를 거부하거나 즉시 삭제해 저장 자체가 실패한다.
        store.save(SESSION_ID, Purpose.ADMIN, Instant.now().minusSeconds(60));

        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(redis).expire(eq(SESSION_KEY), ttl.capture(), eq(TimeUnit.SECONDS));
        assertThat(ttl.getValue()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void 패스키_등록_세션은_인덱스_Set에_추가한다() {
        store.save(SESSION_ID, Purpose.PASSKEY_REGISTRATION, Instant.now().plusSeconds(300));

        verify(sets).add(REGISTRATION_INDEX, SESSION_ID);
        verify(sets, never()).remove(REGISTRATION_INDEX, SESSION_ID);
    }

    @Test
    void 일반_ADMIN_세션은_등록_인덱스에서_제거한다() {
        // 등록 세션이 ADMIN으로 승격될 때 인덱스에 남으면 이후 일괄 폐기 대상이 어긋난다.
        store.save(SESSION_ID, Purpose.ADMIN, Instant.now().plusSeconds(300));

        verify(sets).remove(REGISTRATION_INDEX, SESSION_ID);
        verify(sets, never()).add(REGISTRATION_INDEX, SESSION_ID);
    }

    // ---------------------------------------------------------------- find

    @Test
    void 유효한_세션을_purpose와_만료시각으로_복원한다() {
        Instant expiresAt = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MILLIS);
        stubStoredSession("ADMIN", Long.toString(expiresAt.toEpochMilli()));

        Optional<SessionEntry> found = store.find(SESSION_ID);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().purpose()).isEqualTo(Purpose.ADMIN);
        assertThat(found.orElseThrow().expiresAt()).isEqualTo(expiresAt);
    }

    @ParameterizedTest(name = "sessionId=[{0}]이면 Redis를 조회하지 않는다")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void 빈_세션ID는_Redis_조회_없이_비어있다(String sessionId) {
        assertThat(store.find(sessionId)).isEmpty();

        verify(hashes, never()).get(anyString(), anyString());
    }

    @Test
    void 만료된_세션은_비어있고_키를_삭제한다() {
        // TTL 경합으로 키가 아직 남아 있어도 애플리케이션이 만료를 강제해야 한다.
        stubStoredSession("ADMIN", Long.toString(Instant.now().minusSeconds(1).toEpochMilli()));

        assertThat(store.find(SESSION_ID)).isEmpty();

        verify(redis).delete(SESSION_KEY);
    }

    @Test
    void purpose_필드가_없으면_비어있다() {
        when(hashes.get(SESSION_KEY, "purpose")).thenReturn(null);
        when(hashes.get(SESSION_KEY, "expiresAt")).thenReturn("123");

        assertThat(store.find(SESSION_ID)).isEmpty();
    }

    @Test
    void 만료시각_필드가_없으면_비어있다() {
        when(hashes.get(SESSION_KEY, "purpose")).thenReturn("ADMIN");
        when(hashes.get(SESSION_KEY, "expiresAt")).thenReturn(null);

        assertThat(store.find(SESSION_ID)).isEmpty();
    }

    @ParameterizedTest(name = "손상된 payload purpose={0} expiresAt={1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "ADMIN,        not-a-number",
            "UNKNOWN_ROLE, 1777075500000",
    })
    void 손상된_payload는_키를_삭제하고_비어있다(String purpose, String expiresAt) {
        stubStoredSession(purpose, expiresAt);

        assertThat(store.find(SESSION_ID)).isEmpty();

        verify(redis).delete(SESSION_KEY);
    }

    // ---------------------------------------------------------------- revoke

    @Test
    void 세션_폐기는_키와_등록_인덱스를_함께_지운다() {
        store.revoke(SESSION_ID);

        verify(redis).delete(SESSION_KEY);
        verify(sets).remove(REGISTRATION_INDEX, SESSION_ID);
    }

    @Test
    void null_세션_폐기는_아무것도_하지_않는다() {
        store.revoke(null);

        verify(redis, never()).delete(anyString());
        verify(sets, never()).remove(anyString(), anyString());
    }

    @Test
    void 다른_등록세션만_폐기하고_유지대상은_남긴다() {
        when(sets.members(REGISTRATION_INDEX)).thenReturn(Set.of("keep-me", "drop-1", "drop-2"));

        store.revokeOtherRegistrationSessions("keep-me");

        verify(redis).delete(PREFIX + "session:drop-1");
        verify(redis).delete(PREFIX + "session:drop-2");
        verify(sets).remove(REGISTRATION_INDEX, "drop-1");
        verify(sets).remove(REGISTRATION_INDEX, "drop-2");
        verify(redis, never()).delete(PREFIX + "session:keep-me");
        verify(sets, never()).remove(REGISTRATION_INDEX, "keep-me");
    }

    @Test
    void 등록세션_인덱스가_비어있으면_아무것도_지우지_않는다() {
        when(sets.members(REGISTRATION_INDEX)).thenReturn(Set.of());

        store.revokeOtherRegistrationSessions("keep-me");

        verify(redis, never()).delete(anyString());
    }

    @Test
    void 등록세션_인덱스가_null이어도_예외없이_넘어간다() {
        when(sets.members(REGISTRATION_INDEX)).thenReturn(null);

        store.revokeOtherRegistrationSessions("keep-me");

        verify(redis, never()).delete(anyString());
    }

    @Test
    void 유지대상이_null이면_모든_등록세션을_폐기한다() {
        when(sets.members(REGISTRATION_INDEX)).thenReturn(Set.of("drop-1"));

        store.revokeOtherRegistrationSessions(null);

        verify(redis).delete(PREFIX + "session:drop-1");
        verify(sets).remove(REGISTRATION_INDEX, "drop-1");
    }

    // ---------------------------------------------------------------- helpers

    private void stubStoredSession(String purpose, String expiresAt) {
        when(hashes.get(SESSION_KEY, "purpose")).thenReturn(purpose);
        when(hashes.get(SESSION_KEY, "expiresAt")).thenReturn(expiresAt);
    }
}
