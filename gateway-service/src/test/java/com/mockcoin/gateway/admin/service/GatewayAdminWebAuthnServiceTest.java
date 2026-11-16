package com.mockcoin.gateway.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Set;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import com.mockcoin.gateway.admin.integration.AdminWebAuthnChallengeStore;
import com.mockcoin.gateway.admin.integration.AdminWebAuthnChallengeStore.Purpose;
import com.mockcoin.gateway.admin.integration.GatewayAdminCredentialRepository;
import com.mockcoin.gateway.admin.integration.InMemoryAdminCredentialStore;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.data.ByteArray;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GatewayAdminWebAuthnServiceTest {

    @Test
    void registrationRequestUsesYubicoSerializationAndFiveMinuteChallengeTtl() throws Exception {
        AdminWebAuthnChallengeStore challengeStore = mock(AdminWebAuthnChallengeStore.class);
        GatewayAdminWebAuthnService service = new GatewayAdminWebAuthnService(
                properties(),
                new GatewayAdminCredentialRepository(new InMemoryAdminCredentialStore()),
                challengeStore,
                new ObjectMapper());
        service.init();
        Instant beforeGeneration = Instant.now();
        ArgumentCaptor<String> requestJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);

        service.generateRegistrationOptions();

        verify(challengeStore).save(
                eq(Purpose.REGISTRATION),
                anyString(),
                requestJson.capture(),
                expiresAt.capture());
        assertThat(PublicKeyCredentialCreationOptions.fromJson(requestJson.getValue())).isNotNull();
        assertThat(expiresAt.getValue())
                .isBetween(beforeGeneration.plusSeconds(299), beforeGeneration.plusSeconds(301));
        verify(challengeStore).purgeExpired(any(Instant.class));
    }

    @Test
    void loginRequestUsesYubicoSerializationAndFiveMinuteChallengeTtl() throws Exception {
        AdminWebAuthnChallengeStore challengeStore = mock(AdminWebAuthnChallengeStore.class);
        InMemoryAdminCredentialStore credentialStore = new InMemoryAdminCredentialStore();
        credentialStore.save(new AdminCredentialRecord(
                "admin",
                new ByteArray(new byte[]{1}),
                new ByteArray(new byte[]{2}),
                new ByteArray(new byte[]{3}),
                0L));
        GatewayAdminWebAuthnService service = new GatewayAdminWebAuthnService(
                properties(),
                new GatewayAdminCredentialRepository(credentialStore),
                challengeStore,
                new ObjectMapper());
        service.init();
        Instant beforeGeneration = Instant.now();
        ArgumentCaptor<String> requestJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);

        service.generateLoginOptions();

        verify(challengeStore).save(
                eq(Purpose.LOGIN),
                anyString(),
                requestJson.capture(),
                expiresAt.capture());
        assertThat(AssertionRequest.fromJson(requestJson.getValue())).isNotNull();
        assertThat(expiresAt.getValue())
                .isBetween(beforeGeneration.plusSeconds(299), beforeGeneration.plusSeconds(301));
        verify(challengeStore).purgeExpired(any(Instant.class));
    }

    private static GatewayAdminProperties properties() {
        return new GatewayAdminProperties(
                "internal-token",
                "user-token",
                "trading-token",
                "market-token",
                "ingestor-token",
                "community-token",
                "/admin",
                "admin",
                "secret",
                "localhost",
                "MockCoin Admin",
                Set.of("http://localhost"),
                false,
                false,
                true,
                true);
    }
}
