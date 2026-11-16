package com.mockcoin.gateway.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

// gateway 요청에 적용할 rate limit 규칙을 고른다
@Component
public final class GatewayRateLimitPolicy {
    private static final Duration WINDOW_DURATION = Duration.ofMinutes(1);

    private final GatewaySecurityHardeningProperties securityProperties;
    private final GatewayAdminProperties adminProperties;
    private final GatewayTokenAuthenticator tokenAuthenticator;

    // 보안 설정, 관리자 설정, 토큰 인증기를 주입받는다.
    public GatewayRateLimitPolicy(
            GatewaySecurityHardeningProperties securityProperties,
            GatewayAdminProperties adminProperties,
            GatewayTokenAuthenticator tokenAuthenticator
    ) {
        this.securityProperties = securityProperties;
        this.adminProperties = adminProperties;
        this.tokenAuthenticator = tokenAuthenticator;
    }

    // OPTIONS를 제외한 /api/** 와 관리자 API 요청에만 rate limit을 적용한다.
    boolean appliesTo(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = normalizePath(request.getRequestURI());
        return path.startsWith("/api/") || path.startsWith(adminProperties.basePath() + "/api/");
    }

    // 요청에 적용할 한도 목록을 만든다(IP 전역 한도 + 경로별 한도).
    List<Rule> rulesFor(HttpServletRequest request) {
        String path = normalizePath(request.getRequestURI());
        String method = request.getMethod();
        String ip = resolveClientIp(request);
        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule("gateway_ip", "global:ip:" + ip, securityProperties.getIpRequestsPerMinute(), WINDOW_DURATION));

        if (path.startsWith(adminProperties.basePath() + "/api/")) {
            rules.addAll(resolveAdminRules(request, path, method, ip));
        } else {
            rules.addAll(resolvePublicApiRules(request, path, method, ip));
        }
        return rules;
    }

    // 일반 API 요청의 경로·메서드별 한도를 고른다(인증, 사용자 전체, 프로필, 주문, 결제, 시세, 계정 조회).
    private List<Rule> resolvePublicApiRules(HttpServletRequest request, String path, String method, String ip) {
        List<Rule> rules = new ArrayList<>();
        if (path.startsWith("/api/v1/auth/") && !(isGet(method) && "/api/v1/auth/me".equals(path))) {
            rules.add(new Rule(
                    "gateway_auth",
                    "auth:ip:" + ip,
                    securityProperties.getAuthRequestsPerWindow(),
                    Duration.ofSeconds(securityProperties.getAuthWindowSeconds())
            ));
            return rules;
        }

        String subject = userOrIpSubject(request, ip);
        if (subject.startsWith("uid:")) {
            rules.add(new Rule(
                    "gateway_user",
                    "user:all:" + subject,
                    securityProperties.getAuthenticatedRequestsPerMinute(),
                    WINDOW_DURATION
            ));
        }

        if (isPost(method) && "/api/v1/me/profile/nickname".equals(path)) {
            rules.add(new Rule(
                    "gateway_nickname",
                    "profile:nickname:minute:" + subject,
                    securityProperties.getNicknameRequestsPerMinute(),
                    WINDOW_DURATION
            ));
            return rules;
        }

        if (isPost(method) && path.startsWith("/api/v1/me/profile/")) {
            rules.add(new Rule(
                    "gateway_profile_write",
                    "profile:write:" + subject,
                    securityProperties.getProfileWriteRequestsPerMinute(),
                    WINDOW_DURATION
            ));
            return rules;
        }

        if (isPut(method) && "/api/v1/me/notification-agreements".equals(path)) {
            rules.add(new Rule(
                    "gateway_notification_write",
                    "notification:write:" + subject,
                    securityProperties.getProfileWriteRequestsPerMinute(),
                    WINDOW_DURATION
            ));
            return rules;
        }

        if (isOrderWrite(path, method)) {
            rules.add(new Rule("gateway_order", "order:" + subject, securityProperties.getOrderRequestsPerMinute(), WINDOW_DURATION));
            return rules;
        }

        if (isIapWrite(path, method)) {
            rules.add(new Rule("gateway_iap", "billing:iap:" + subject, securityProperties.getIapRequestsPerMinute(), WINDOW_DURATION));
            return rules;
        }

        if (isResetOrRefundWrite(path, method)) {
            rules.add(new Rule("gateway_reset", "billing:reset:" + subject, securityProperties.getResetRequestsPerMinute(), WINDOW_DURATION));
            return rules;
        }

        if (isPost(method) && path.startsWith("/api/v1/billing/")) {
            rules.add(new Rule(
                    "gateway_billing_write",
                    "billing:write:" + subject,
                    securityProperties.getBillingWriteRequestsPerMinute(),
                    WINDOW_DURATION
            ));
            return rules;
        }

        if (isGet(method) && isMarketCandleRead(path)) {
            rules.add(new Rule(
                    "gateway_market_candles",
                    "market:candles:" + subject,
                    securityProperties.getMarketCandleRequestsPerMinute(),
                    WINDOW_DURATION
            ));
            return rules;
        }

        if (isGet(method) && path.startsWith("/api/v1/markets")) {
            rules.add(new Rule(
                    "gateway_market_read",
                    "market:read:" + subject,
                    securityProperties.getMarketReadRequestsPerMinute(),
                    WINDOW_DURATION
            ));
            return rules;
        }

        if (isGet(method) && isAccountRead(path)) {
            rules.add(new Rule(
                    "gateway_account_read",
                    "account:read:" + subject,
                    securityProperties.getAccountReadRequestsPerMinute(),
                    WINDOW_DURATION
            ));
        }
        return rules;
    }

    // 관리자 API 요청의 한도를 고른다(로그인, 민감 쓰기, 일반 쓰기, 조회).
    private List<Rule> resolveAdminRules(HttpServletRequest request, String path, String method, String ip) {
        List<Rule> rules = new ArrayList<>();
        String subject = adminSubject(request, ip);
        String authBase = adminProperties.basePath() + "/api/auth";
        String facadeBase = adminProperties.basePath() + "/api/v1/admin-console";
        if (isGet(method) && (authBase + "/status").equals(path)) {
            rules.add(new Rule(
                    "gateway_admin_read",
                    "admin:read:" + subject,
                    securityProperties.getAdminReadRequestsPerMinute(),
                    WINDOW_DURATION
            ));
            return rules;
        }
        if (path.startsWith(authBase + "/")) {
            rules.add(new Rule(
                    "gateway_admin_auth",
                    "admin:auth:ip:" + ip,
                    securityProperties.getAdminAuthRequestsPerWindow(),
                    Duration.ofSeconds(securityProperties.getAdminAuthWindowSeconds())
            ));
            return rules;
        }
        if (!path.startsWith(facadeBase + "/")) {
            rules.add(new Rule(
                    "gateway_admin_read",
                    "admin:read:" + subject,
                    securityProperties.getAdminReadRequestsPerMinute(),
                    WINDOW_DURATION
            ));
            return rules;
        }
        if (isAdminSensitiveWrite(path, method, facadeBase)) {
            rules.add(new Rule(
                    "gateway_admin_sensitive",
                    "admin:sensitive:" + subject,
                    securityProperties.getAdminSensitiveRequestsPerMinute(),
                    WINDOW_DURATION
            ));
            return rules;
        }
        if (isUnsafe(method)) {
            rules.add(new Rule(
                    "gateway_admin_write",
                    "admin:write:" + subject,
                    securityProperties.getAdminWriteRequestsPerMinute(),
                    WINDOW_DURATION
            ));
            return rules;
        }
        rules.add(new Rule(
                "gateway_admin_read",
                "admin:read:" + subject,
                securityProperties.getAdminReadRequestsPerMinute(),
                WINDOW_DURATION
        ));
        return rules;
    }

    // 주문 생성 또는 주문 취소 요청인지 확인한다.
    private static boolean isOrderWrite(String path, String method) {
        return isPost(method)
                && ("/api/v1/orders".equals(path)
                || (path.startsWith("/api/v1/orders/") && path.endsWith("/cancel")));
    }

    // 인앱결제 지급·검증 요청인지 확인한다.
    private static boolean isIapWrite(String path, String method) {
        return isPost(method)
                && ("/api/v1/billing/iap/grant".equals(path) || "/api/v1/billing/iap/verify".equals(path));
    }

    // 지갑 초기화 또는 초기화권 환불 요청인지 확인한다.
    private static boolean isResetOrRefundWrite(String path, String method) {
        return isPost(method)
                && ("/api/v1/me/reset".equals(path)
                || (path.startsWith("/api/v1/me/reset-pass-purchases/") && path.endsWith("/refund")));
    }

    // 종목 캔들 조회 경로인지 확인한다.
    private static boolean isMarketCandleRead(String path) {
        return path.startsWith("/api/v1/markets/") && path.endsWith("/candles");
    }

    // 내 계정 관련 조회 경로(프로필, 포트폴리오, 주문, 랭킹, 결제 등)인지 확인한다.
    private static boolean isAccountRead(String path) {
        return "/api/v1/me/profile".equals(path)
                || "/api/v1/me/notification-agreements".equals(path)
                || "/api/v1/auth/me".equals(path)
                || "/api/v1/portfolio".equals(path)
                || "/api/v1/orders".equals(path)
                || "/api/v1/me/reset-passes".equals(path)
                || "/api/v1/me/reset-pass-purchases".equals(path)
                || path.startsWith("/api/v1/rankings/")
                || path.startsWith("/api/v1/billing/");
    }

    // 관리자 민감 쓰기(IAP, 초기화권, 지갑 보정, 재계산) 요청인지 확인한다.
    private static boolean isAdminSensitiveWrite(String path, String method, String facadeBase) {
        if (!isUnsafe(method)) {
            return false;
        }
        return path.startsWith(facadeBase + "/iap/")
                || path.contains("/reset-passes")
                || path.contains("/wallet/ensure")
                || path.endsWith("/recalculate");
    }

    // 한도 키의 주체를 사용자 ID → 토큰 해시 → IP 순으로 정하고, 확인된 사용자 ID는 요청 속성에 저장한다.
    private String userOrIpSubject(HttpServletRequest request, String ip) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        Long userId = safeResolveUserIdQuietly(authorization);
        if (userId != null) {
            request.setAttribute(GatewayTokenAuthenticator.AUTHENTICATED_USER_ID_REQUEST_ATTRIBUTE, userId);
            return "uid:" + userId;
        }
        if (authorization != null && !authorization.isBlank()) {
            return "token:" + stableHash(normalizeAuthorization(authorization));
        }
        return "ip:" + ip;
    }

    // 인증된 관리자면 공통 관리자 주체를, 아니면 IP를 한도 키의 주체로 쓴다.
    private String adminSubject(HttpServletRequest request, String ip) {
        String adminToken = request.getHeader(GatewayAdminProperties.ADMIN_TOKEN_HEADER);
        String sessionId = extractCookie(request, GatewayAdminProperties.SESSION_COOKIE_NAME);
        if (isConfiguredAdminToken(adminToken)
                || (sessionId != null && !sessionId.isBlank() && hasAuthenticatedAdminSession())) {
            return "principal:admin";
        }
        return "ip:" + ip;
    }

    // 헤더의 관리자 토큰이 설정된 내부 토큰과 같은지 확인한다.
    private boolean isConfiguredAdminToken(String token) {
        String expected = adminProperties.internalToken();
        return token != null && !token.isBlank() && !expected.isBlank() && expected.equals(token);
    }

    // 현재 SecurityContext에 관리자 콘솔 권한으로 인증된 세션이 있는지 확인한다.
    private static boolean hasAuthenticatedAdminSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN_CONSOLE".equals(authority.getAuthority()));
    }

    // 이름으로 쿠키 값을 찾고, 없으면 null을 반환한다.
    private static String extractCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    // 원본 토큰을 키에 남기지 않도록 SHA-256 해시 앞 22자를 만든다(실패 시 hashCode).
    private static String stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed).substring(0, 22);
        } catch (Exception exception) {
            return Integer.toHexString(value.hashCode());
        }
    }

    // 토큰에서 사용자 ID를 꺼내고, 예외가 나면 null을 반환한다.
    private Long safeResolveUserIdQuietly(String authorization) {
        try {
            return tokenAuthenticator.resolveUserIdQuietly(authorization);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // 신뢰 프록시 설정을 반영해 실제 클라이언트 IP를 구한다.
    private String resolveClientIp(HttpServletRequest request) {
        return TrustedProxyResolver.resolveClientIp(
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                securityProperties.getTrustedProxyCidrs()
        );
    }

    // HTTP 메서드가 GET인지 확인한다.
    private static boolean isGet(String method) {
        return "GET".equalsIgnoreCase(method);
    }

    // HTTP 메서드가 POST인지 확인한다.
    private static boolean isPost(String method) {
        return "POST".equalsIgnoreCase(method);
    }

    // HTTP 메서드가 PUT인지 확인한다.
    private static boolean isPut(String method) {
        return "PUT".equalsIgnoreCase(method);
    }

    // 상태를 바꾸는 메서드(POST/PUT/PATCH/DELETE)인지 확인한다.
    private static boolean isUnsafe(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    // 각 경로 세그먼트의 ';' 뒤 matrix 파라미터를 잘라 경로 우회를 막는다.
    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String[] segments = path.split("/", -1);
        StringBuilder normalized = new StringBuilder(path.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                normalized.append('/');
            }
            String segment = segments[i];
            int matrixIndex = segment.indexOf(';');
            normalized.append(matrixIndex >= 0 ? segment.substring(0, matrixIndex) : segment);
        }
        return normalized.toString();
    }

    // Authorization 값을 "Bearer <토큰>" 형식으로 통일해 같은 토큰이 같은 해시가 되게 한다.
    private static String normalizeAuthorization(String authorization) {
        return authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? "Bearer " + authorization.substring(7).trim()
                : "Bearer " + authorization.trim();
    }

    // 검사할 한도 하나. scope는 메트릭 라벨용 고정 이름이고, key는 사용자/IP가 섞여 라벨로 쓸 수 없다.
    record Rule(String scope, String key, int maxRequests, Duration window) {
    }
}
