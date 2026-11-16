package com.mockcoin.gateway.admin.domain;

import com.yubico.webauthn.data.ByteArray;

// 등록된 admin WebAuthn 자격증명의 영속 표현(공개키/userHandle/서명 카운트)
// in-memory/Redis AdminCredentialStore 어댑터가 함께 써서 저장 방식이 GatewayAdminCredentialRepository 밖으로 드러나지 않게 한다.
public record AdminCredentialRecord(
        String username,
        ByteArray credentialId,
        ByteArray userHandle,
        ByteArray publicKeyCose,
        long signatureCount) {
}
