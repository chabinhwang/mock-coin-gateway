package com.mockcoin.gateway.admin.integration;
import java.util.Optional;

import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import com.yubico.webauthn.data.ByteArray;

// admin WebAuthn 자격증명·서명 카운트 저장소 포트(in-memory/Redis 어댑터 선택 가능)
// GatewayAdminCredentialRepository의 Yubico CredentialRepository 구현을 뒷받침하며, prev-server RedisAdminPasskeyStore와
// 같은 의미라 Redis를 켜면 패스키가 gateway 재시작 후에도 남고 pod 간에 공유된다.
public interface AdminCredentialStore {

    // 등록된 패스키가 하나라도 있는지 반환한다
    boolean hasRegisteredPasskey();

    // credentialId로 자격증명을 조회한다
    Optional<AdminCredentialRecord> findByCredentialId(ByteArray credentialId);

    // username으로 자격증명을 조회한다
    Optional<AdminCredentialRecord> findByUsername(String username);

    // userHandle로 자격증명을 조회한다
    Optional<AdminCredentialRecord> findByUserHandle(ByteArray userHandle);

    // 기존 자격증명을 교체 저장한다(관리자가 명시적으로 패스키를 교체할 때 사용).
    void save(AdminCredentialRecord record);

    // 등록된 자격증명이 하나도 없을 때만 원자적으로 저장하고, 저장 여부를 반환한다.
    boolean saveIfAbsent(AdminCredentialRecord record);

    // 해당 자격증명의 서명 카운트를 갱신하고, 자격증명이 없으면 아무것도 하지 않는다.
    void updateSignCount(ByteArray credentialId, long signatureCount);
}
