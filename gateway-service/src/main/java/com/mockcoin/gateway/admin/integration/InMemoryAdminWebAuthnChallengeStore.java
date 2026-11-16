package com.mockcoin.gateway.admin.integration;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

// Redis 비활성 시 사용되는 admin WebAuthn challenge in-memory 저장소
@Component
@ConditionalOnMissingBean(name = "redisAdminWebAuthnChallengeStore")
public class InMemoryAdminWebAuthnChallengeStore implements AdminWebAuthnChallengeStore {
    private final Map<ChallengeKey, ChallengeEntry> challenges = new ConcurrentHashMap<>();

    @Override
    public void save(Purpose purpose, String challengeId, String requestJson, Instant expiresAt) {
        challenges.put(new ChallengeKey(purpose, challengeId), new ChallengeEntry(requestJson, expiresAt));
    }

    // challenge를 꺼내면서 제거하고, 입력이 비었거나 미존재·만료면 empty를 반환한다.
    @Override
    public Optional<String> take(Purpose purpose, String challengeId) {
        if (purpose == null || challengeId == null || challengeId.isBlank()) {
            return Optional.empty();
        }
        ChallengeEntry entry = challenges.remove(new ChallengeKey(purpose, challengeId));
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.requestJson());
    }

    // 만료 시각이 now 이전인 challenge를 모두 제거한다.
    @Override
    public void purgeExpired(Instant now) {
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record ChallengeKey(Purpose purpose, String challengeId) {
    }

    private record ChallengeEntry(String requestJson, Instant expiresAt) {
    }
}
