package com.mockcoin.gateway.admin.integration;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.mockcoin.gateway.admin.config.GatewayAdminRedisProperties;
import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.exception.Base64UrlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

// Redis 기반 admin WebAuthn 자격증명 저장소(다중 gateway 인스턴스 공유, gateway 재시작 후에도 유지)
// 자격증명은 <prefix>credential:<credentialIdBase64Url>에 JSON 문서로 두고, 등록 ID 인덱스 Set(<prefix>credentials)으로
// 키 공간 스캔 없이 존재 확인·서명 카운트 갱신을 하며, 서명 카운트는 문서 자리에서 갱신한다.
public class RedisAdminCredentialStore implements AdminCredentialStore {
    private static final Logger log = LoggerFactory.getLogger(RedisAdminCredentialStore.class);
    private static final RedisScript<Long> SAVE_IF_ABSENT_SCRIPT = new DefaultRedisScript<>("""
            local indexKey = KEYS[1]
            local newCredentialKey = KEYS[2]
            local credentialKeyPrefix = ARGV[1]
            local newCredentialId = ARGV[2]
            local payload = ARGV[3]
            local credentialIds = redis.call('SMEMBERS', indexKey)
            for _, credentialId in ipairs(credentialIds) do
                local existingCredentialKey = credentialKeyPrefix .. credentialId
                if redis.call('EXISTS', existingCredentialKey) == 1 then
                    return 0
                end
                redis.call('SREM', indexKey, credentialId)
            end
            redis.call('SET', newCredentialKey, payload)
            redis.call('SADD', indexKey, newCredentialId)
            return 1
            """, Long.class);
    private static final RedisScript<Long> REPLACE_SCRIPT = new DefaultRedisScript<>("""
            local indexKey = KEYS[1]
            local newCredentialKey = KEYS[2]
            local credentialKeyPrefix = ARGV[1]
            local newCredentialId = ARGV[2]
            local payload = ARGV[3]
            local credentialIds = redis.call('SMEMBERS', indexKey)
            for _, credentialId in ipairs(credentialIds) do
                if credentialId ~= newCredentialId then
                    redis.call('DEL', credentialKeyPrefix .. credentialId)
                end
            end
            redis.call('DEL', indexKey)
            redis.call('SET', newCredentialKey, payload)
            redis.call('SADD', indexKey, newCredentialId)
            return 1
            """, Long.class);
    private static final RedisScript<Long> UPDATE_SIGN_COUNT_SCRIPT = new DefaultRedisScript<>("""
            local credentialKey = KEYS[1]
            local indexKey = KEYS[2]
            local nextSignCount = tonumber(ARGV[1])
            local credentialId = ARGV[2]
            local payload = redis.call('GET', credentialKey)
            if not payload then
                redis.call('SREM', indexKey, credentialId)
                return 0
            end
            local decoded, document = pcall(cjson.decode, payload)
            if not decoded or type(document) ~= 'table' or not nextSignCount then
                redis.call('DEL', credentialKey)
                redis.call('SREM', indexKey, credentialId)
                return -1
            end
            local currentSignCount = tonumber(document['signatureCount']) or 0
            if nextSignCount > currentSignCount then
                document['signatureCount'] = nextSignCount
                redis.call('SET', credentialKey, cjson.encode(document))
            end
            return 1
            """, Long.class);
    private static final RedisScript<Long> DELETE_UNINDEXED_ORPHAN_SCRIPT = new DefaultRedisScript<>("""
            local credentialKey = KEYS[1]
            local indexKey = KEYS[2]
            local expectedPayload = ARGV[1]
            local credentialId = ARGV[2]
            if redis.call('SISMEMBER', indexKey, credentialId) == 0
                    and redis.call('GET', credentialKey) == expectedPayload then
                redis.call('DEL', credentialKey)
                return 1
            end
            return 0
            """, Long.class);
    private static final RedisScript<Long> REMOVE_MISSING_INDEX_SCRIPT = new DefaultRedisScript<>("""
            local credentialKey = KEYS[1]
            local indexKey = KEYS[2]
            local credentialId = ARGV[1]
            if redis.call('SISMEMBER', indexKey, credentialId) == 1
                    and redis.call('EXISTS', credentialKey) == 0 then
                redis.call('SREM', indexKey, credentialId)
                return 1
            end
            return 0
            """, Long.class);
    private static final RedisScript<Long> DELETE_INVALID_INDEXED_PAYLOAD_SCRIPT = new DefaultRedisScript<>("""
            local credentialKey = KEYS[1]
            local indexKey = KEYS[2]
            local expectedPayload = ARGV[1]
            local credentialId = ARGV[2]
            if redis.call('SISMEMBER', indexKey, credentialId) == 1
                    and redis.call('GET', credentialKey) == expectedPayload then
                redis.call('DEL', credentialKey)
                redis.call('SREM', indexKey, credentialId)
                return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final GatewayAdminRedisProperties properties;
    private final ObjectMapper objectMapper;

    // Redis 템플릿, 관리자 Redis 설정, ObjectMapper를 주입받는다.
    public RedisAdminCredentialStore(StringRedisTemplate redis, GatewayAdminRedisProperties properties, ObjectMapper objectMapper) {
        this.redis = redis;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // 인덱스에 유효한 자격증명이 하나라도 남아 있는지 확인한다.
    @Override
    public boolean hasRegisteredPasskey() {
        return !loadAll().isEmpty();
    }

    @Override
    public Optional<AdminCredentialRecord> findByCredentialId(ByteArray credentialId) {
        return findIndexedCredential(credentialId.getBase64Url());
    }

    // 인덱스의 전체 자격증명 중 username이 일치하는 첫 항목을 반환한다(username이 null이면 empty).
    @Override
    public Optional<AdminCredentialRecord> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return loadAll().stream().filter(record -> username.equals(record.username())).findFirst();
    }

    // 인덱스의 전체 자격증명 중 userHandle이 일치하는 첫 항목을 반환한다(userHandle이 null이면 empty).
    @Override
    public Optional<AdminCredentialRecord> findByUserHandle(ByteArray userHandle) {
        if (userHandle == null) {
            return Optional.empty();
        }
        return loadAll().stream().filter(record -> userHandle.equals(record.userHandle())).findFirst();
    }

    // Lua 스크립트로 다른 자격증명을 모두 지우고 새 자격증명으로 원자적으로 교체하며, 실패하면 예외를 던진다.
    @Override
    public void save(AdminCredentialRecord record) {
        String credentialIdBase64 = record.credentialId().getBase64Url();
        Long result = redis.execute(
                REPLACE_SCRIPT,
                List.of(indexKey(), credentialKey(record.credentialId())),
                credentialKeyPrefix(),
                credentialIdBase64,
                serialize(record));
        if (result == null || result != 1L) {
            throw new IllegalStateException("Failed to replace admin credential atomically");
        }
    }

    // 유효한 자격증명이 없을 때만 Lua 스크립트로 원자적으로 저장하고(남은 깨진 인덱스 항목은 정리), 저장 여부를 반환한다.
    @Override
    public boolean saveIfAbsent(AdminCredentialRecord record) {
        if (!loadAll().isEmpty()) {
            return false;
        }
        String credentialId = record.credentialId().getBase64Url();
        Long result = redis.execute(
                SAVE_IF_ABSENT_SCRIPT,
                List.of(indexKey(), credentialKey(record.credentialId())),
                credentialKeyPrefix(),
                credentialId,
                serialize(record));
        return result != null && result == 1L;
    }

    // Lua 스크립트로 새 서명 카운트가 더 클 때만 갱신하고, 문서가 없거나 깨졌으면 문서·인덱스 항목을 정리한다.
    @Override
    public void updateSignCount(ByteArray credentialId, long signatureCount) {
        redis.execute(
                UPDATE_SIGN_COUNT_SCRIPT,
                List.of(credentialKey(credentialId), indexKey()),
                Long.toString(signatureCount),
                credentialId.getBase64Url());
    }

    // 인덱스 Set의 ID마다 자격증명을 읽어 유효한 것만 모은다.
    private java.util.List<AdminCredentialRecord> loadAll() {
        Set<String> ids = redis.opsForSet().members(indexKey());
        if (ids == null || ids.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<AdminCredentialRecord> records = new java.util.ArrayList<>(ids.size());
        for (String storedId : ids) {
            findIndexedCredential(storedId).ifPresent(records::add);
        }
        return records;
    }

    // 인덱스에 있는 ID의 문서만 읽어 파싱하고, 인덱스 없는 고아 문서나 문서 없는 인덱스 항목은 정리한 뒤 empty를 반환한다.
    private Optional<AdminCredentialRecord> findIndexedCredential(String credentialId) {
        String key = credentialKey(credentialId);
        if (!Boolean.TRUE.equals(redis.opsForSet().isMember(indexKey(), credentialId))) {
            String orphanPayload = redis.opsForValue().get(key);
            if (orphanPayload != null) {
                redis.execute(
                        DELETE_UNINDEXED_ORPHAN_SCRIPT,
                        List.of(key, indexKey()),
                        orphanPayload,
                        credentialId);
            }
            return Optional.empty();
        }

        String payload = redis.opsForValue().get(key);
        if (payload == null) {
            redis.execute(
                    REMOVE_MISSING_INDEX_SCRIPT,
                    List.of(key, indexKey()),
                    credentialId);
            return Optional.empty();
        }
        return parse(payload, key, credentialId);
    }

    // JSON 문서를 레코드로 파싱하고, 형식 오류나 인덱스 ID 불일치면 문서와 인덱스 항목을 지운 뒤 empty를 반환한다.
    private Optional<AdminCredentialRecord> parse(String payload, String key, String indexedCredentialId) {
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(payload);
            ByteArray credentialId = ByteArray.fromBase64Url(node.get("credentialId").asText());
            if (!credentialId.getBase64Url().equals(indexedCredentialId)) {
                throw new IllegalArgumentException("Credential payload does not match its index member");
            }
            ByteArray userHandle = ByteArray.fromBase64Url(node.get("userHandle").asText());
            ByteArray publicKeyCose = ByteArray.fromBase64Url(node.get("publicKeyCose").asText());
            long signatureCount = node.get("signatureCount").asLong();
            String username = node.get("username").asText();
            return Optional.of(new AdminCredentialRecord(username, credentialId, userHandle, publicKeyCose, signatureCount));
        } catch (Base64UrlException | RuntimeException exception) {
            log.warn("Invalid admin credential payload at key={}, dropping", key, exception);
            redis.execute(
                    DELETE_INVALID_INDEXED_PAYLOAD_SCRIPT,
                    List.of(key, indexKey()),
                    payload,
                    indexedCredentialId);
            return Optional.empty();
        }
    }

    // 레코드를 바이트 필드는 Base64URL로 바꾼 JSON 문자열로 직렬화한다.
    private String serialize(AdminCredentialRecord record) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("username", record.username());
        node.put("credentialId", record.credentialId().getBase64Url());
        node.put("userHandle", record.userHandle().getBase64Url());
        node.put("publicKeyCose", record.publicKeyCose().getBase64Url());
        node.put("signatureCount", record.signatureCount());
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to serialise admin credential", exception);
        }
    }

    private String credentialKey(ByteArray credentialId) {
        return credentialKey(credentialId.getBase64Url());
    }

    private String credentialKey(String credentialId) {
        return credentialKeyPrefix() + credentialId;
    }

    private String credentialKeyPrefix() {
        return properties.redisKeyPrefix() + "credential:";
    }

    private String indexKey() {
        return properties.redisKeyPrefix() + "credentials";
    }
}
