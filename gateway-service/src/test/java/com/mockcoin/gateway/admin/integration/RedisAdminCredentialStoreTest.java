package com.mockcoin.gateway.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.admin.config.GatewayAdminRedisProperties;
import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import com.yubico.webauthn.data.ByteArray;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisAdminCredentialStoreTest {
    private static final String INDEX_KEY = "gateway:admin:credentials";

    @Test
    void hasRegisteredPasskeyRemovesIndexMemberWhenPayloadIsMissing() {
        Fixture fixture = fixture();
        String credentialId = record(1).credentialId().getBase64Url();
        when(fixture.sets.members(INDEX_KEY)).thenReturn(Set.of(credentialId));
        when(fixture.sets.isMember(INDEX_KEY, credentialId)).thenReturn(true);
        when(fixture.values.get(credentialKey(credentialId))).thenReturn(null);
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertThat(fixture.store.hasRegisteredPasskey()).isFalse();

        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(fixture.redis).execute(
                script.capture(),
                eq(List.of(credentialKey(credentialId), INDEX_KEY)),
                any(Object[].class));
        assertThat(script.getValue().getScriptAsString())
                .contains("EXISTS', credentialKey")
                .contains("SREM', indexKey, credentialId");
        verify(fixture.sets, never()).remove(INDEX_KEY, credentialId);
    }

    @Test
    void corruptPayloadRemovesCredentialAndIndexMember() {
        Fixture fixture = fixture();
        String credentialId = record(1).credentialId().getBase64Url();
        when(fixture.sets.members(INDEX_KEY)).thenReturn(Set.of(credentialId));
        when(fixture.sets.isMember(INDEX_KEY, credentialId)).thenReturn(true);
        when(fixture.values.get(credentialKey(credentialId))).thenReturn("not-json");
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertThat(fixture.store.findByUsername("admin")).isEmpty();

        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(fixture.redis).execute(
                script.capture(),
                eq(List.of(credentialKey(credentialId), INDEX_KEY)),
                any(Object[].class));
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('GET', credentialKey) == expectedPayload")
                .contains("redis.call('SISMEMBER', indexKey, credentialId) == 1");
        verify(fixture.redis, never()).delete(credentialKey(credentialId));
        verify(fixture.sets, never()).remove(INDEX_KEY, credentialId);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void findByCredentialIdRejectsAndConditionallyDeletesUnindexedOrphan() throws Exception {
        Fixture fixture = fixture();
        AdminCredentialRecord record = record(1);
        String credentialId = record.credentialId().getBase64Url();
        String payload = credentialPayload(record);
        when(fixture.sets.isMember(INDEX_KEY, credentialId)).thenReturn(false);
        when(fixture.values.get(credentialKey(credentialId))).thenReturn(payload);
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertThat(fixture.store.findByCredentialId(record.credentialId())).isEmpty();

        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(fixture.redis).execute(
                script.capture(),
                eq(List.of(credentialKey(credentialId), INDEX_KEY)),
                any(Object[].class));
        assertThat(script.getValue().getScriptAsString())
                .contains("redis.call('SISMEMBER', indexKey, credentialId) == 0")
                .contains("redis.call('GET', credentialKey) == expectedPayload")
                .contains("redis.call('DEL', credentialKey)");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void saveIfAbsentUsesOneAtomicRedisScriptOverExistingKeys() {
        Fixture fixture = fixture();
        AdminCredentialRecord record = record(1);
        String credentialId = record.credentialId().getBase64Url();
        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertThat(fixture.store.saveIfAbsent(record)).isTrue();

        verify(fixture.redis).execute(
                script.capture(),
                eq(List.of(INDEX_KEY, credentialKey(credentialId))),
                any(Object[].class));
        assertThat(script.getValue().getScriptAsString())
                .doesNotContain("EXISTS', newCredentialKey")
                .contains("redis.call('SET', newCredentialKey, payload)")
                .contains("redis.call('SADD', indexKey, newCredentialId)");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void rotationUsesOneAtomicRedisScriptOverExistingKeys() {
        Fixture fixture = fixture();
        AdminCredentialRecord record = record(1);
        String credentialId = record.credentialId().getBase64Url();
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        fixture.store.save(record);

        verify(fixture.redis).execute(
                any(RedisScript.class),
                eq(List.of(INDEX_KEY, credentialKey(credentialId))),
                any(Object[].class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void signCountUpdateIsAtomicAndNeverWritesALowerCount() {
        Fixture fixture = fixture();
        AdminCredentialRecord record = record(1);
        String credentialId = record.credentialId().getBase64Url();
        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);

        fixture.store.updateSignCount(record.credentialId(), 9L);

        verify(fixture.redis).execute(
                script.capture(),
                eq(List.of(credentialKey(credentialId), INDEX_KEY)),
                any(Object[].class));
        assertThat(script.getValue().getScriptAsString())
                .contains("if nextSignCount > currentSignCount then");
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForValue()).thenReturn(values);
        RedisAdminCredentialStore store = new RedisAdminCredentialStore(
                redis,
                new GatewayAdminRedisProperties(true, "gateway:admin:"),
                new ObjectMapper());
        return new Fixture(redis, sets, values, store);
    }

    private static String credentialKey(String credentialId) {
        return "gateway:admin:credential:" + credentialId;
    }

    private static String credentialPayload(AdminCredentialRecord record) throws Exception {
        return new ObjectMapper().writeValueAsString(java.util.Map.of(
                "username", record.username(),
                "credentialId", record.credentialId().getBase64Url(),
                "userHandle", record.userHandle().getBase64Url(),
                "publicKeyCose", record.publicKeyCose().getBase64Url(),
                "signatureCount", record.signatureCount()));
    }

    private static AdminCredentialRecord record(int seed) {
        return new AdminCredentialRecord(
                "admin",
                new ByteArray(new byte[]{(byte) seed}),
                new ByteArray(new byte[]{(byte) (seed + 10)}),
                new ByteArray(new byte[]{(byte) (seed + 20)}),
                0L);
    }

    private record Fixture(
            StringRedisTemplate redis,
            SetOperations<String, String> sets,
            ValueOperations<String, String> values,
            RedisAdminCredentialStore store) {
    }
}
