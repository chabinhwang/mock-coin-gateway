package com.mockcoin.gateway.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.mockcoin.gateway.admin.integration.AdminWebAuthnChallengeStore.Purpose;
import org.junit.jupiter.api.Test;

class InMemoryAdminWebAuthnChallengeStoreTest {
    private final InMemoryAdminWebAuthnChallengeStore store = new InMemoryAdminWebAuthnChallengeStore();

    @Test
    void challengeIsConsumedExactlyOnce() {
        store.save(Purpose.REGISTRATION, "challenge-1", "request-json", Instant.now().plusSeconds(300));

        assertThat(store.take(Purpose.REGISTRATION, "challenge-1")).contains("request-json");
        assertThat(store.take(Purpose.REGISTRATION, "challenge-1")).isEmpty();
    }

    @Test
    void challengePurposeIsPartOfTheLookupKey() {
        store.save(Purpose.REGISTRATION, "shared-id", "registration-json", Instant.now().plusSeconds(300));

        assertThat(store.take(Purpose.LOGIN, "shared-id")).isEmpty();
        assertThat(store.take(Purpose.REGISTRATION, "shared-id")).contains("registration-json");
    }

    @Test
    void missingOrBlankChallengeIdIsRejectedWithoutThrowing() {
        assertThat(store.take(Purpose.LOGIN, null)).isEmpty();
        assertThat(store.take(Purpose.LOGIN, " ")).isEmpty();
    }

    @Test
    void expiredChallengeCannotBeConsumed() {
        store.save(Purpose.LOGIN, "expired", "request-json", Instant.now().minusSeconds(1));

        assertThat(store.take(Purpose.LOGIN, "expired")).isEmpty();
    }
}
