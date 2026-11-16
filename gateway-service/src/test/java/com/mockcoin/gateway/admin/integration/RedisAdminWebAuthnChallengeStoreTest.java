package com.mockcoin.gateway.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import com.mockcoin.gateway.admin.config.GatewayAdminRedisProperties;
import com.mockcoin.gateway.admin.integration.AdminWebAuthnChallengeStore.Purpose;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisAdminWebAuthnChallengeStoreTest {

    @Test
    void savesChallengeUnderCompatiblePrefixWithTtl() {
        Fixture fixture = fixture();

        fixture.store.save(Purpose.REGISTRATION, "challenge-1", "request-json", Instant.now().plusSeconds(300));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(fixture.values).set(
                eq("gateway:admin:challenge:registration:challenge-1"),
                eq("request-json"),
                ttl.capture());
        assertThat(ttl.getValue()).isBetween(Duration.ofSeconds(298), Duration.ofSeconds(300));
    }

    @Test
    void consumesChallengeAtomicallyWithGetAndDelete() {
        Fixture fixture = fixture();
        when(fixture.values.getAndDelete("gateway:admin:challenge:login:challenge-1"))
                .thenReturn("request-json")
                .thenReturn(null);

        assertThat(fixture.store.take(Purpose.LOGIN, "challenge-1")).contains("request-json");
        assertThat(fixture.store.take(Purpose.LOGIN, "challenge-1")).isEmpty();

        verify(fixture.values, org.mockito.Mockito.times(2))
                .getAndDelete("gateway:admin:challenge:login:challenge-1");
    }

    @Test
    void missingChallengeIdDoesNotTouchRedis() {
        Fixture fixture = fixture();

        assertThat(fixture.store.take(Purpose.LOGIN, null)).isEmpty();
        assertThat(fixture.store.take(Purpose.LOGIN, " ")).isEmpty();

        verifyNoInteractions(fixture.values);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisAdminWebAuthnChallengeStore store = new RedisAdminWebAuthnChallengeStore(
                redis,
                new GatewayAdminRedisProperties(true, "gateway:admin:"));
        return new Fixture(values, store);
    }

    private record Fixture(
            ValueOperations<String, String> values,
            RedisAdminWebAuthnChallengeStore store) {
    }
}
