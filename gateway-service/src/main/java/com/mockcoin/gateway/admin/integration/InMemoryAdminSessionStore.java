package com.mockcoin.gateway.admin.integration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mockcoin.gateway.admin.integration.AdminSessionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Redis 비활성(mockcoin.gateway.admin.redis-enabled=false, 기본값) 시 사용되는 admin 세션 토큰 in-memory 저장소
// 1분마다 절대 만료가 지난 항목을 정리해 버려진 세션이 쌓이지 않게 하며, 운영자 세션 몇 개뿐이라 전체 스캔으로 충분하다.
@Component
@ConditionalOnMissingBean(name = "redisAdminSessionStore")
public class InMemoryAdminSessionStore implements AdminSessionStore {
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(String sessionId, Purpose purpose, Instant expiresAt) {
        sessions.put(sessionId, new SessionEntry(purpose, expiresAt));
    }

    // 세션을 조회하고, ID가 비었거나 미존재면 empty, 만료됐으면 제거한 뒤 empty를 반환한다.
    @Override
    public Optional<SessionEntry> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    // sessionId가 null이 아니면 해당 세션을 제거한다.
    @Override
    public void revoke(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    // keepSessionId를 제외한 패스키 등록용 세션을 모두 제거한다.
    @Override
    public void revokeOtherRegistrationSessions(String keepSessionId) {
        sessions.entrySet().removeIf(entry ->
                !entry.getKey().equals(keepSessionId)
                        && entry.getValue().purpose() == Purpose.PASSKEY_REGISTRATION);
    }

    // 만료 시각이 now 이전인 세션을 모두 제거한다.
    public void purgeExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    // 버려진 세션 토큰이 쌓이지 않도록 주기적으로(기본 60초) 만료 세션을 정리한다.
    @Scheduled(fixedDelayString = "${mockcoin.gateway.admin.in-memory-sweep-millis:60000}")
    void scheduledPurge() {
        purgeExpired(Instant.now());
    }
}
