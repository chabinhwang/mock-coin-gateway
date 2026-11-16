package com.mockcoin.gateway.admin.integration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import com.mockcoin.gateway.admin.integration.AdminCredentialStore;
import com.yubico.webauthn.data.ByteArray;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

// Redis 비활성(mockcoin.gateway.admin.redis-enabled=false, 기본값) 시 사용되는 단일 admin 자격증명 in-memory 저장소
// prev-server의 단일 admin 모델처럼 자격증명을 최대 1개만 두며, AtomicReference로 bootstrap 등록의 save-if-absent를
// 원자적으로 보장해 동시에 끝난 등록이 서로 덮어쓰지 않게 한다.
@Component
@ConditionalOnMissingBean(name = "redisAdminCredentialStore")
public class InMemoryAdminCredentialStore implements AdminCredentialStore {
    private final AtomicReference<AdminCredentialRecord> credential = new AtomicReference<>();

    @Override
    public boolean hasRegisteredPasskey() {
        return credential.get() != null;
    }

    // 저장된 자격증명의 credentialId가 일치할 때만 반환한다.
    @Override
    public Optional<AdminCredentialRecord> findByCredentialId(ByteArray credentialId) {
        AdminCredentialRecord record = credential.get();
        if (record == null || !record.credentialId().equals(credentialId)) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    // 저장된 자격증명의 username이 일치할 때만 반환한다(username이 null이면 empty).
    @Override
    public Optional<AdminCredentialRecord> findByUsername(String username) {
        AdminCredentialRecord record = credential.get();
        if (record == null || username == null || !record.username().equals(username)) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    // 저장된 자격증명의 userHandle이 일치할 때만 반환한다.
    @Override
    public Optional<AdminCredentialRecord> findByUserHandle(ByteArray userHandle) {
        AdminCredentialRecord record = credential.get();
        if (record == null || !record.userHandle().equals(userHandle)) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    public void save(AdminCredentialRecord record) {
        credential.set(record);
    }

    // 저장된 자격증명이 없을 때만 CAS로 저장해 동시에 끝난 등록이 서로 덮어쓰지 않게 한다.
    @Override
    public boolean saveIfAbsent(AdminCredentialRecord record) {
        return credential.compareAndSet(null, record);
    }

    // credentialId가 같고 새 카운트가 더 클 때만 서명 카운트를 바꾼 새 레코드로 교체한다.
    @Override
    public void updateSignCount(ByteArray credentialId, long signatureCount) {
        credential.updateAndGet(current -> {
            if (current == null || !current.credentialId().equals(credentialId)) {
                return current;
            }
            if (signatureCount <= current.signatureCount()) {
                return current;
            }
            return new AdminCredentialRecord(
                    current.username(),
                    current.credentialId(),
                    current.userHandle(),
                    current.publicKeyCose(),
                    signatureCount);
        });
    }
}
