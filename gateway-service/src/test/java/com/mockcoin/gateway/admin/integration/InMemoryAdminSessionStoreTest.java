package com.mockcoin.gateway.admin.integration;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.mockcoin.gateway.admin.integration.AdminSessionStore;
import com.mockcoin.gateway.admin.integration.AdminSessionStore.Purpose;
import org.junit.jupiter.api.Test;

class InMemoryAdminSessionStoreTest {

    private final InMemoryAdminSessionStore store = new InMemoryAdminSessionStore();

    @Test
    void saveAndFindReturnsEntry() {
        Instant expiresAt = Instant.now().plusSeconds(60);
        store.save("token-1", Purpose.ADMIN, expiresAt);

        assertThat(store.find("token-1"))
                .map(AdminSessionStore.SessionEntry::purpose)
                .contains(Purpose.ADMIN);
    }

    @Test
    void findReturnsEmptyForExpiredEntry() {
        store.save("token-1", Purpose.ADMIN, Instant.now().minusSeconds(1));

        assertThat(store.find("token-1")).isEmpty();
    }

    @Test
    void revokeRemovesEntry() {
        store.save("token-1", Purpose.ADMIN, Instant.now().plusSeconds(60));
        store.revoke("token-1");

        assertThat(store.find("token-1")).isEmpty();
    }

    @Test
    void revokeOtherRegistrationSessionsKeepsTargetAndDropsOthers() {
        Instant later = Instant.now().plusSeconds(60);
        store.save("keep", Purpose.PASSKEY_REGISTRATION, later);
        store.save("drop-1", Purpose.PASSKEY_REGISTRATION, later);
        store.save("drop-2", Purpose.PASSKEY_REGISTRATION, later);
        store.save("admin", Purpose.ADMIN, later);

        store.revokeOtherRegistrationSessions("keep");

        assertThat(store.find("keep")).isPresent();
        assertThat(store.find("drop-1")).isEmpty();
        assertThat(store.find("drop-2")).isEmpty();
        assertThat(store.find("admin")).isPresent();
    }

    @Test
    void purgeExpiredRemovesPastEntriesOnly() {
        Instant past = Instant.now().minusSeconds(10);
        Instant future = Instant.now().plusSeconds(60);
        store.save("expired", Purpose.ADMIN, past);
        store.save("alive", Purpose.ADMIN, future);

        store.purgeExpired(Instant.now());

        assertThat(store.find("alive")).isPresent();
        // Even after purge, expired entry must not be findable.
        assertThat(store.find("expired")).isEmpty();
    }
}
