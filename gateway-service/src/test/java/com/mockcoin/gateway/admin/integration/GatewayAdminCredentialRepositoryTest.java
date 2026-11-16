package com.mockcoin.gateway.admin.integration;
import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import static org.assertj.core.api.Assertions.assertThat;

import com.mockcoin.gateway.admin.integration.InMemoryAdminCredentialStore;
import com.yubico.webauthn.data.ByteArray;
import org.junit.jupiter.api.Test;

class GatewayAdminCredentialRepositoryTest {
    @Test
    void bootstrapSaveIsAtomicAndDoesNotReplaceExistingCredential() {
        GatewayAdminCredentialRepository repository = new GatewayAdminCredentialRepository(new InMemoryAdminCredentialStore());
        AdminCredentialRecord first = record(1);
        AdminCredentialRecord second = record(2);

        assertThat(repository.saveIfAbsent(first)).isTrue();
        assertThat(repository.saveIfAbsent(second)).isFalse();
        assertThat(repository.lookupAll(first.credentialId())).hasSize(1);
        assertThat(repository.lookupAll(second.credentialId())).isEmpty();
    }

    @Test
    void adminRotationCanReplaceExistingCredential() {
        GatewayAdminCredentialRepository repository = new GatewayAdminCredentialRepository(new InMemoryAdminCredentialStore());
        AdminCredentialRecord first = record(1);
        AdminCredentialRecord second = record(2);

        repository.save(first);
        repository.save(second);

        assertThat(repository.lookupAll(first.credentialId())).isEmpty();
        assertThat(repository.lookupAll(second.credentialId())).hasSize(1);
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
