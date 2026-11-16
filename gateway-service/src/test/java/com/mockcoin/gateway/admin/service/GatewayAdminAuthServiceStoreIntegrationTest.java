package com.mockcoin.gateway.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import com.mockcoin.gateway.admin.integration.GatewayAdminCredentialRepository;
import com.mockcoin.gateway.admin.integration.InMemoryAdminCredentialStore;
import com.mockcoin.gateway.admin.integration.InMemoryAdminLockoutStore;
import com.mockcoin.gateway.admin.integration.InMemoryAdminSessionStore;
import com.yubico.webauthn.data.ByteArray;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Integration test for {@link GatewayAdminAuthService} wiring against the in-memory store ports.
 * Verifies the service no longer relies on its own internal maps and instead delegates to the
 * port abstractions so a Redis-backed adapter can be substituted for multi-instance deployments.
 */
class GatewayAdminAuthServiceStoreIntegrationTest {

    private static final String IP = "1.2.3.4";

    private GatewayAdminAuthService newService() {
        return newService(false, true, "local");
    }

    private GatewayAdminAuthService newService(boolean devPasswordBypassEnabled, String... activeProfiles) {
        return newService(devPasswordBypassEnabled, true, activeProfiles);
    }

    private GatewayAdminAuthService newService(
            boolean devPasswordBypassEnabled,
            boolean passkeyEnabled,
            String... activeProfiles
    ) {
        GatewayAdminProperties properties = new GatewayAdminProperties(
                "internal-token", "user-token", "trading-token", "market-token", "ingestor-token", "community-token",
                "/admin", "admin", "secret", "localhost", "MockCoin Admin", Set.of(), false, devPasswordBypassEnabled, passkeyEnabled, true);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new GatewayAdminAuthService(
                properties,
                new GatewayAdminCredentialRepository(new InMemoryAdminCredentialStore()),
                new InMemoryAdminSessionStore(),
                new InMemoryAdminLockoutStore(),
                environment);
    }

    @Test
    void successfulPasswordLoginCreatesRegistrationSession() {
        GatewayAdminAuthService service = newService();

        Optional<String> sessionId = service.login("admin", "secret", IP);

        assertThat(sessionId).isPresent();
        assertThat(service.validateRegistrationSession(sessionId.get())).isTrue();
        assertThat(service.validateSession(sessionId.get())).isFalse();
    }

    @Test
    void promoteRegistrationSessionUpgradesToAdminAndDropsOtherRegistrationSessions() {
        GatewayAdminAuthService service = newService();
        String sessionA = service.createRegistrationSession();
        String sessionB = service.createRegistrationSession();

        assertThat(service.promoteRegistrationSession(sessionA)).isTrue();

        assertThat(service.validateSession(sessionA)).isTrue();
        assertThat(service.validateRegistrationSession(sessionB)).isFalse();
    }

    @Test
    void repeatedFailureLocksOutClientIpUntilCleared() {
        GatewayAdminAuthService service = newService();
        for (int i = 0; i < GatewayAdminProperties.MAX_LOGIN_ATTEMPTS; i++) {
            assertThat(service.login("admin", "wrong", IP)).isEmpty();
        }

        assertThat(service.isLocked(IP)).isTrue();
    }

    @Test
    void passkeyRegistrationBlocksFurtherPasswordLogin() {
        GatewayAdminAuthService service = newService();
        // Inject an existing credential as if a passkey were already registered.
        GatewayAdminCredentialRepository repository = new GatewayAdminCredentialRepository(new InMemoryAdminCredentialStore());
        repository.save(new AdminCredentialRecord(
                "admin",
                new ByteArray(new byte[]{1}),
                new ByteArray(new byte[]{2}),
                new ByteArray(new byte[]{3}),
                0L));
        GatewayAdminProperties properties = new GatewayAdminProperties(
                "internal-token", "user-token", "trading-token", "market-token", "ingestor-token", "community-token",
                "/admin", "admin", "secret", "localhost", "MockCoin Admin", Set.of(), false, false, true, true);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        GatewayAdminAuthService serviceWithPasskey = new GatewayAdminAuthService(
                properties,
                repository,
                new InMemoryAdminSessionStore(),
                new InMemoryAdminLockoutStore(),
                environment);

        assertThat(serviceWithPasskey.login("admin", "secret", IP)).isEmpty();
    }

    @Test
    void passwordOnlyLoginCreatesAdminSessionEvenWhenPasskeyExists() {
        GatewayAdminCredentialRepository repository = new GatewayAdminCredentialRepository(new InMemoryAdminCredentialStore());
        repository.save(new AdminCredentialRecord(
                "admin",
                new ByteArray(new byte[]{1}),
                new ByteArray(new byte[]{2}),
                new ByteArray(new byte[]{3}),
                0L));
        GatewayAdminProperties properties = new GatewayAdminProperties(
                "internal-token", "user-token", "trading-token", "market-token", "ingestor-token", "community-token",
                "/admin", "admin", "secret", "localhost", "MockCoin Admin", Set.of(), false, false, false, true);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        GatewayAdminAuthService service = new GatewayAdminAuthService(
                properties,
                repository,
                new InMemoryAdminSessionStore(),
                new InMemoryAdminLockoutStore(),
                environment);

        Optional<String> sessionId = service.login("admin", "secret", IP);

        assertThat(sessionId).isPresent();
        assertThat(service.validateSession(sessionId.get())).isTrue();
        assertThat(service.getAuthStatus(IP, sessionId.get()))
                .containsEntry("passkeyEnabled", false)
                .containsEntry("hasPasskey", true)
                .containsEntry("registrationRequired", false)
                .containsEntry("authenticated", true);
    }

    @Test
    void localDevPasswordBypassCreatesAdminSessionWithoutPasskeyRegistration() {
        GatewayAdminAuthService service = newService(true, "local");

        Optional<String> sessionId = service.login("admin", "secret", IP);

        assertThat(sessionId).isPresent();
        assertThat(service.validateSession(sessionId.get())).isTrue();
        assertThat(service.validateRegistrationSession(sessionId.get())).isTrue();
        assertThat(service.getAuthStatus(IP, sessionId.get())).containsEntry("devPasswordBypassEnabled", true);
    }

    @Test
    void devPasswordBypassIsIgnoredOutsideLocalProfile() {
        GatewayAdminAuthService service = newService(true, "prod");

        Optional<String> sessionId = service.login("admin", "secret", IP);

        assertThat(sessionId).isPresent();
        assertThat(service.validateRegistrationSession(sessionId.get())).isTrue();
        assertThat(service.validateSession(sessionId.get())).isFalse();
        assertThat(service.getAuthStatus(IP, sessionId.get())).containsEntry("devPasswordBypassEnabled", false);
    }

    @Test
    void logoutInvalidatesSession() {
        GatewayAdminAuthService service = newService();
        String token = service.createAdminSession();
        assertThat(service.validateSession(token)).isTrue();

        service.logout(token);

        assertThat(service.validateSession(token)).isFalse();
    }
}
