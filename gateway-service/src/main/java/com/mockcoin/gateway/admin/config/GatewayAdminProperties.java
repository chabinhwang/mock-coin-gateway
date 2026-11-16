package com.mockcoin.gateway.admin.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

// admin facade 토큰/콘솔 자격증명/WebAuthn RP/세션 쿠키 설정
@ConfigurationProperties(prefix = "mockcoin.admin")
public record GatewayAdminProperties(
        String internalToken,
        String userServiceToken,
        String tradingServiceToken,
        String marketServiceToken,
        String ingestorServiceToken,
        String communityServiceToken,
        String basePath,
        String username,
        String password,
        String webauthnRpId,
        String webauthnRpName,
        Set<String> webauthnOrigins,
        boolean secureCookie,
        boolean devPasswordBypassEnabled,
        boolean passkeyEnabled,
        boolean enabled
) {
    public static final String ADMIN_TOKEN_HEADER = "X-Mockcoin-Admin-Token";
    public static final String SESSION_COOKIE_NAME = "ADMIN_SESSION";
    public static final int SESSION_TIMEOUT_SECONDS = 1800;
    public static final int MAX_LOGIN_ATTEMPTS = 5;
    public static final int LOCKOUT_DURATION_SECONDS = 1800;

    // null 토큰·자격증명은 빈 문자열로 바꾸고, base path와 WebAuthn RP 이름·ID·origin은 기본값으로 정규화한다.
    public GatewayAdminProperties {
        internalToken = internalToken == null ? "" : internalToken;
        userServiceToken = userServiceToken == null ? "" : userServiceToken;
        tradingServiceToken = tradingServiceToken == null ? "" : tradingServiceToken;
        marketServiceToken = marketServiceToken == null ? "" : marketServiceToken;
        ingestorServiceToken = ingestorServiceToken == null ? "" : ingestorServiceToken;
        communityServiceToken = communityServiceToken == null ? "" : communityServiceToken;
        basePath = normalizeBasePath(basePath);
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        webauthnRpId = webauthnRpId == null || webauthnRpId.isBlank() ? "localhost" : webauthnRpId;
        webauthnRpName = webauthnRpName == null || webauthnRpName.isBlank() ? "MockCoin Admin" : webauthnRpName;
        webauthnOrigins = webauthnOrigins == null ? Set.of() : Set.copyOf(webauthnOrigins);
    }

    // base path가 '/'로 시작하고 끝에 '/'가 없도록 맞추며, 비어 있으면 /admin을 쓴다.
    private static String normalizeBasePath(String value) {
        if (value == null || value.isBlank()) {
            return "/admin";
        }
        String normalized = value.startsWith("/") ? value : "/" + value;
        return normalized.endsWith("/") && normalized.length() > 1
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }
}
