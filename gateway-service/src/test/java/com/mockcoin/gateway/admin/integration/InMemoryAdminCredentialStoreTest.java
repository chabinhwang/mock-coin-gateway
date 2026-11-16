package com.mockcoin.gateway.admin.integration;
import static org.assertj.core.api.Assertions.assertThat;

import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import com.mockcoin.gateway.admin.integration.AdminCredentialStore;
import com.yubico.webauthn.data.ByteArray;
import org.junit.jupiter.api.Test;

class InMemoryAdminCredentialStoreTest {

    private final AdminCredentialStore store = new InMemoryAdminCredentialStore();

    @Test
    void saveIfAbsentDoesNotReplaceExistingCredential() {
        assertThat(store.saveIfAbsent(record(1))).isTrue();
        assertThat(store.saveIfAbsent(record(2))).isFalse();
        assertThat(store.findByCredentialId(record(1).credentialId())).isPresent();
        assertThat(store.findByCredentialId(record(2).credentialId())).isEmpty();
    }

    @Test
    void saveReplacesPreviousCredential() {
        store.save(record(1));
        store.save(record(2));

        assertThat(store.findByCredentialId(record(1).credentialId())).isEmpty();
        assertThat(store.findByCredentialId(record(2).credentialId())).isPresent();
    }

    @Test
    void updateSignCountIsAppliedToMatchingCredentialOnly() {
        store.save(record(1));
        store.updateSignCount(record(1).credentialId(), 99L);

        assertThat(store.findByCredentialId(record(1).credentialId()))
                .map(AdminCredentialRecord::signatureCount)
                .contains(99L);
    }

    @Test
    void updateSignCountIsNoopForUnknownCredential() {
        store.save(record(1));
        store.updateSignCount(record(2).credentialId(), 50L);

        assertThat(store.findByCredentialId(record(1).credentialId()))
                .map(AdminCredentialRecord::signatureCount)
                .contains(0L);
    }

    @Test
    void updateSignCountDoesNotMoveBackwards() {
        AdminCredentialRecord current = record(1);
        store.save(new AdminCredentialRecord(
                current.username(),
                current.credentialId(),
                current.userHandle(),
                current.publicKeyCose(),
                10L));

        store.updateSignCount(current.credentialId(), 3L);

        assertThat(store.findByCredentialId(current.credentialId()))
                .map(AdminCredentialRecord::signatureCount)
                .contains(10L);
    }

    private static AdminCredentialRecord record(int seed) {
        return new AdminCredentialRecord(
                "admin",
                new ByteArray(new byte[]{(byte) seed}),
                new ByteArray(new byte[]{(byte) (seed + 10)}),
                new ByteArray(new byte[]{(byte) (seed + 20)}),
                0L);
    }
}
