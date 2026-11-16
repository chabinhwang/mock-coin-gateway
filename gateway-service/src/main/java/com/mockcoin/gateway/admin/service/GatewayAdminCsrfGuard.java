package com.mockcoin.gateway.admin.service;

import java.net.URI;
import java.util.Locale;

import com.mockcoin.gateway.global.error.GatewayErrorCode;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

// 쿠키 인증 admin facade unsafe 요청의 Origin/Referer same-origin 검증을 수행한다
@Component
public class GatewayAdminCsrfGuard {
    private final GatewayAdminFacadeService facadeService;

    // 관리자 토큰 확인에 쓸 facade 서비스를 주입받는다.
    public GatewayAdminCsrfGuard(GatewayAdminFacadeService facadeService) {
        this.facadeService = facadeService;
    }

    // 쿠키 기반 admin 요청이면 same-origin Origin/Referer를 강제한다 (header token 경로는 면제)
    public void verifyUnsafeCookieRequest(HttpServletRequest request, String token) {
        if (isSafeMethod(request.getMethod()) || facadeService.isGatewayAdminTokenValid(token)) {
            return;
        }
        if (GatewayAdminSessionAuthFilter.extractSessionCookie(request) == null) {
            return;
        }
        String source = firstNonBlank(request.getHeader("Origin"), request.getHeader("Referer"));
        if (source == null || !sameOrigin(source, request)) {
            throw GatewayErrorCode.ADMIN_ORIGIN_REJECTED.exception();
        }
    }

    // GET·HEAD·OPTIONS·TRACE처럼 상태를 바꾸지 않는 메서드인지 확인한다.
    private static boolean isSafeMethod(String method) {
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method) || HttpMethod.TRACE.matches(method);
    }

    // 두 값 중 처음으로 비어 있지 않은 값을 반환하고, 둘 다 비면 null을 반환한다.
    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    // Origin/Referer의 scheme·host·port가 프록시 헤더를 반영한 요청 출처와 같은지 확인한다(파싱 실패 시 false).
    private static boolean sameOrigin(String source, HttpServletRequest request) {
        try {
            URI uri = URI.create(source);
            String requestScheme = forwardedValue(request, "X-Forwarded-Proto", request.getScheme()).toLowerCase(Locale.ROOT);
            String requestHost = forwardedHost(request);
            int requestPort = forwardedPort(request, requestScheme);
            int sourcePort = effectivePort(uri.getScheme(), uri.getPort());
            return requestScheme.equalsIgnoreCase(uri.getScheme())
                    && requestHost.equalsIgnoreCase(uri.getHost())
                    && requestPort == sourcePort;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    // X-Forwarded-Host 첫 값에서 포트를 뺀 호스트를, 헤더가 없으면 서버 이름을 반환한다.
    private static String forwardedHost(HttpServletRequest request) {
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (forwardedHost != null && !forwardedHost.isBlank()) {
            return forwardedHost.split(",", 2)[0].trim().split(":", 2)[0];
        }
        return request.getServerName();
    }

    // X-Forwarded-Port 첫 값을, 없거나 숫자가 아니면 서버 포트(미지정 시 scheme 기본 포트)를 반환한다.
    private static int forwardedPort(HttpServletRequest request, String scheme) {
        String forwardedPort = request.getHeader("X-Forwarded-Port");
        if (forwardedPort != null && !forwardedPort.isBlank()) {
            try {
                return Integer.parseInt(forwardedPort.split(",", 2)[0].trim());
            } catch (NumberFormatException ignored) {
                return effectivePort(scheme, request.getServerPort());
            }
        }
        return effectivePort(scheme, request.getServerPort());
    }

    // 헤더의 첫 값(쉼표 앞)을 반환하고, 헤더가 비어 있으면 fallback을 반환한다.
    private static String forwardedValue(HttpServletRequest request, String header, String fallback) {
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.split(",", 2)[0].trim();
    }

    // 포트가 지정되지 않았으면 scheme 기본 포트(https 443, 그 외 80)를 반환한다.
    private static int effectivePort(String scheme, int port) {
        if (port > 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
