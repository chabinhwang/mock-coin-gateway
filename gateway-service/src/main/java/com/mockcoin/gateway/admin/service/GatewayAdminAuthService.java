package com.mockcoin.gateway.admin.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.integration.GatewayAdminCredentialRepository;
import com.mockcoin.gateway.admin.integration.AdminLockoutStore;
import com.mockcoin.gateway.admin.integration.AdminSessionStore;
import com.mockcoin.gateway.admin.integration.AdminSessionStore.Purpose;
import com.mockcoin.gateway.admin.integration.AdminSessionStore.SessionEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

// admin 콘솔 로그인/세션/잠금 흐름 (password 전용, 또는 패스키 등록 promotion)
@Service
public class GatewayAdminAuthService {
    private static final Logger log = LoggerFactory.getLogger(GatewayAdminAuthService.class);

    private final GatewayAdminProperties properties;
    private final GatewayAdminCredentialRepository credentialRepository;
    private final AdminSessionStore sessionStore;
    private final AdminLockoutStore lockoutStore;
    private final Environment environment;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private volatile String passwordHash;

    // 의존성을 주입받고, 설정된 관리자 비밀번호를 BCrypt 해시로 만들어 둔다.
    public GatewayAdminAuthService(
            GatewayAdminProperties properties,
            GatewayAdminCredentialRepository credentialRepository,
            AdminSessionStore sessionStore,
            AdminLockoutStore lockoutStore,
            Environment environment) {
        this.properties = properties;
        this.credentialRepository = credentialRepository;
        this.sessionStore = sessionStore;
        this.lockoutStore = lockoutStore;
        this.environment = environment;
        this.passwordHash = passwordEncoder.encode(properties.password());
    }

    // id/password를 검증한다. 패스키가 꺼져 있으면 전체 admin 세션을, 켜져 있으면 등록 세션을 발급한다
    public Optional<String> login(String username, String password, String ip) {
        if (isLocked(ip)) {
            log.warn("Gateway admin login attempt from locked IP={}", ip);
            return Optional.empty();
        }
        if (properties.username().isBlank() || properties.password().isBlank()) {
            log.warn("Gateway admin login rejected because credentials are not configured");
            recordFailure(ip);
            return Optional.empty();
        }
        if (!properties.username().equals(username) || !passwordEncoder.matches(password == null ? "" : password, passwordHash)) {
            recordFailure(ip);
            log.warn("Gateway admin login failed from IP={}", ip);
            return Optional.empty();
        }
        if (!properties.passkeyEnabled()) {
            log.info("Gateway admin password login issued full admin session from IP={}", ip);
            clearFailures(ip);
            return Optional.of(createAdminSession());
        }
        if (isDevPasswordBypassAllowed()) {
            log.warn("Gateway admin dev password bypass issued full admin session from IP={}", ip);
            clearFailures(ip);
            return Optional.of(createAdminSession());
        }
        if (credentialRepository.hasRegisteredPasskey()) {
            log.warn("Gateway admin password login rejected because passkey is registered, IP={}", ip);
            return Optional.empty();
        }
        clearFailures(ip);
        return Optional.of(createRegistrationSession());
    }

    // 패스키 로그인 성공 후 전체 admin 권한 세션을 발급한다
    public String createAdminSession() {
        return createSession(Purpose.ADMIN);
    }

    // 패스키 등록만 가능한 bootstrap 세션을 발급한다
    public String createRegistrationSession() {
        return createSession(Purpose.PASSKEY_REGISTRATION);
    }

    // UUID 세션 ID를 만들어 목적과 30분 뒤 만료 시각으로 저장한 뒤 반환한다.
    private String createSession(Purpose purpose) {
        String sessionId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(GatewayAdminProperties.SESSION_TIMEOUT_SECONDS);
        sessionStore.save(sessionId, purpose, expiresAt);
        return sessionId;
    }

    // 세션이 전체 admin 권한을 가지는지 검사한다
    public boolean validateSession(String sessionId) {
        return validateSession(sessionId, Purpose.ADMIN);
    }

    // 세션이 패스키 등록 또는 admin 권한을 가지는지 검사한다
    public boolean validateRegistrationSession(String sessionId) {
        return validateSession(sessionId, Purpose.PASSKEY_REGISTRATION) || validateSession(sessionId, Purpose.ADMIN);
    }

    // 패스키 등록 완료 후 등록 세션을 admin 세션으로 승격하고 다른 등록 세션을 폐기한다
    public boolean promoteRegistrationSession(String sessionId) {
        if (!validateSession(sessionId, Purpose.PASSKEY_REGISTRATION) && !validateSession(sessionId, Purpose.ADMIN)) {
            return false;
        }
        Instant expiresAt = Instant.now().plusSeconds(GatewayAdminProperties.SESSION_TIMEOUT_SECONDS);
        sessionStore.save(sessionId, Purpose.ADMIN, expiresAt);
        sessionStore.revokeOtherRegistrationSessions(sessionId);
        return true;
    }

    // 세션 ID가 비어 있지 않고, 만료되지 않은 세션의 목적이 requiredPurpose와 같은지 확인한다.
    private boolean validateSession(String sessionId, Purpose requiredPurpose) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Optional<SessionEntry> entry = sessionStore.find(sessionId);
        return entry.isPresent() && entry.get().purpose() == requiredPurpose;
    }

    // admin 세션을 폐기한다
    public void logout(String sessionId) {
        sessionStore.revoke(sessionId);
    }

    // 해당 IP가 로그인 잠금 윈도우 내인지 확인한다
    public boolean isLocked(String ip) {
        return lockoutStore.getLockout(ip)
                .map(entry -> entry.lockedUntil() != null && entry.lockedUntil().isAfter(Instant.now()))
                .orElse(false);
    }

    // 콘솔 UI에서 사용할 현재 인증 상태(패스키/잠금/세션) 요약을 반환한다
    public Map<String, Object> getAuthStatus(String ip, String sessionId) {
        return Map.of(
                "passkeyEnabled", properties.passkeyEnabled(),
                "hasPasskey", credentialRepository.hasRegisteredPasskey(),
                "isLocked", isLocked(ip),
                "authenticated", validateSession(sessionId),
                "registrationRequired", properties.passkeyEnabled()
                        && validateRegistrationSession(sessionId)
                        && !validateSession(sessionId),
                "devPasswordBypassEnabled", isDevPasswordBypassAllowed()
        );
    }

    // dev 비밀번호 우회 설정이 켜져 있고 local 프로필이 활성인지 확인한다.
    private boolean isDevPasswordBypassAllowed() {
        return properties.devPasswordBypassEnabled()
                && Arrays.asList(environment.getActiveProfiles()).contains("local");
    }

    // 해당 IP의 로그인 실패를 기록한다(5회 쌓이면 30분 잠금).
    private void recordFailure(String ip) {
        lockoutStore.recordFailure(
                ip,
                GatewayAdminProperties.MAX_LOGIN_ATTEMPTS,
                GatewayAdminProperties.LOCKOUT_DURATION_SECONDS);
    }

    private void clearFailures(String ip) {
        lockoutStore.clear(ip);
    }
}
