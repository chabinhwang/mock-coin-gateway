package com.mockcoin.gateway.admin.integration;

import java.time.Instant;
import java.util.Optional;

// admin WebAuthn 등록/로그인 challenge를 1회 소비 방식으로 저장하는 포트
// 직렬화된 요청은 Yubico 자체 toJson/fromJson으로 만들고 읽으며, 구현체는 소비 시 요청을 원자적으로 제거해야 한다.
public interface AdminWebAuthnChallengeStore {

    // challenge 용도(패스키 등록 / 로그인)
    enum Purpose {
        REGISTRATION,
        LOGIN
    }

    // challenge 요청을 절대 만료 시각까지 저장한다
    void save(Purpose purpose, String challengeId, String requestJson, Instant expiresAt);

    // challenge를 원자적으로 제거하며 조회한다(만료/미존재 시 empty)
    Optional<String> take(Purpose purpose, String challengeId);

    // 만료된 challenge를 정리한다(Redis는 TTL로 처리해 no-op)
    void purgeExpired(Instant now);
}
