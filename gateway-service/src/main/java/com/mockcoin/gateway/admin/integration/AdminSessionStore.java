package com.mockcoin.gateway.admin.integration;
import java.time.Instant;
import java.util.Optional;

// admin 콘솔 세션 토큰 저장소 포트(in-memory 단일 인스턴스 / Redis 다인스턴스 공유)
// 세션마다 용도(전체 admin 또는 패스키 등록 대기)와 절대 만료 시각을 가지며(prev-server RedisAdminSessionStore와 같은 계약),
// 만료는 저장소가 항상 처리하므로 호출자가 조회 결과를 다시 거를 필요가 없다.
public interface AdminSessionStore {

    // 세션 용도: 전체 admin 권한 또는 패스키 등록 전용
    enum Purpose {
        ADMIN,
        PASSKEY_REGISTRATION
    }

    record SessionEntry(Purpose purpose, Instant expiresAt) {
    }

    // 절대 만료 시각과 함께 세션 토큰을 저장한다
    void save(String sessionId, Purpose purpose, Instant expiresAt);

    // 세션 토큰을 조회한다(만료/미존재 시 empty)
    Optional<SessionEntry> find(String sessionId);

    // 세션 토큰을 제거한다
    void revoke(String sessionId);

    // 지정 세션을 제외한 모든 패스키 등록용 세션을 제거한다
    void revokeOtherRegistrationSessions(String keepSessionId);

}
