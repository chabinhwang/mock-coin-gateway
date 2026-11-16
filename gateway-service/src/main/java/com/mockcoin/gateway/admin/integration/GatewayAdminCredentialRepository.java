package com.mockcoin.gateway.admin.integration;
import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import java.util.Optional;
import java.util.Set;

import com.mockcoin.gateway.admin.integration.AdminCredentialStore;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.PublicKeyCredentialType;
import org.springframework.stereotype.Component;

// Yubico CredentialRepository 구현으로 in-memory/Redis AdminCredentialStore를 래핑한다
// 기존 호출자(GatewayAdminWebAuthnService, 컨트롤러 테스트)는 같은 API를 그대로 쓰고, 실제 저장소는 설정으로 고른다.
@Component
public class GatewayAdminCredentialRepository implements CredentialRepository {

    private final AdminCredentialStore store;

    // 관리자 자격증명 저장소를 주입받는다.
    public GatewayAdminCredentialRepository(AdminCredentialStore store) {
        this.store = store;
    }

    // username에 등록된 credentialId를 공개키 descriptor 집합으로 반환한다(없으면 빈 집합).
    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        return store.findByUsername(username)
                .map(record -> Set.of(PublicKeyCredentialDescriptor.builder()
                        .id(record.credentialId())
                        .type(PublicKeyCredentialType.PUBLIC_KEY)
                        .build()))
                .orElseGet(Set::of);
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return store.findByUsername(username).map(AdminCredentialRecord::userHandle);
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        return store.findByUserHandle(userHandle).map(AdminCredentialRecord::username);
    }

    // credentialId로 찾은 자격증명의 userHandle이 일치할 때만 RegisteredCredential로 반환한다.
    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return store.findByCredentialId(credentialId)
                .filter(record -> record.userHandle().equals(userHandle))
                .map(GatewayAdminCredentialRepository::toRegisteredCredential);
    }

    // credentialId에 해당하는 자격증명을 RegisteredCredential 집합으로 반환한다(없으면 빈 집합).
    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return store.findByCredentialId(credentialId)
                .map(record -> Set.of(toRegisteredCredential(record)))
                .orElseGet(Set::of);
    }

    // 패스키가 한 개라도 등록되어 있는지 여부를 반환한다
    public boolean hasRegisteredPasskey() {
        return store.hasRegisteredPasskey();
    }

    // 기존 자격증명을 새 자격증명으로 덮어쓴다(관리자 주도 rotation)
    public void save(AdminCredentialRecord record) {
        store.save(record);
    }

    // 자격증명이 비어 있을 때만 원자적으로 저장한다(bootstrap 등록 보호)
    public boolean saveIfAbsent(AdminCredentialRecord record) {
        return store.saveIfAbsent(record);
    }

    // 패스키 사용 후 서명 카운트를 갱신한다
    public void updateSignCount(ByteArray credentialId, long signatureCount) {
        store.updateSignCount(credentialId, signatureCount);
    }

    // 저장 레코드를 Yubico RegisteredCredential로 변환한다.
    private static RegisteredCredential toRegisteredCredential(AdminCredentialRecord record) {
        return RegisteredCredential.builder()
                .credentialId(record.credentialId())
                .userHandle(record.userHandle())
                .publicKeyCose(record.publicKeyCose())
                .signatureCount(record.signatureCount())
                .build();
    }

}
