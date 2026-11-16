package com.mockcoin.shared.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.Supplier;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mockcoin.shared.api.CommonErrorCode;

// 서비스별 internal endpoint 앞단에서 공유 토큰 헤더를 검증하는 Servlet Filter
public final class InternalTokenAuthenticationFilter implements Filter {
    private final String headerName;
    private final Supplier<String> expectedTokenSupplier;
    private final String notConfiguredReason;

    // 토큰 헤더 이름, 기대 토큰 공급자, 토큰 미설정 시 응답 사유를 받아 필터를 만든다(모두 null 불가).
    public InternalTokenAuthenticationFilter(String headerName, Supplier<String> expectedTokenSupplier, String notConfiguredReason) {
        this.headerName = Objects.requireNonNull(headerName, "headerName must not be null");
        this.expectedTokenSupplier = Objects.requireNonNull(expectedTokenSupplier, "expectedTokenSupplier must not be null");
        this.notConfiguredReason = Objects.requireNonNull(notConfiguredReason, "notConfiguredReason must not be null");
    }

    // 토큰을 담는 요청 헤더 이름
    public String headerName() {
        return headerName;
    }

    // 토큰이 설정되지 않았을 때 응답하는 사유 문구
    public String notConfiguredReason() {
        return notConfiguredReason;
    }

    // 기대 토큰이 비어 있으면 미설정 401, 헤더 토큰이 다르면 거부 401을 응답하고, 일치할 때만 다음 필터로 넘긴다.
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String expected = normalize(expectedTokenSupplier.get());
        if (expected.isBlank()) {
            writeUnauthorized(httpResponse, CommonErrorCode.INTERNAL_TOKEN_NOT_CONFIGURED.code(), notConfiguredReason);
            return;
        }
        if (!constantTimeEquals(expected, httpRequest.getHeader(headerName))) {
            writeUnauthorized(httpResponse, CommonErrorCode.INTERNAL_TOKEN_REJECTED.code(), CommonErrorCode.INTERNAL_TOKEN_REJECTED.reason());
            return;
        }
        chain.doFilter(request, response);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    // 공유 비밀 비교는 일치하는 접두사 길이에 비례해 시간이 달라지면 안 된다.
    // MessageDigest.isEqual은 같은 길이의 입력에 대해 early return하지 않는다.
    private static boolean constantTimeEquals(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    // 401 상태와 ApiResponse 실패 형식의 JSON 본문을 응답에 직접 쓴다.
    private static void writeUnauthorized(HttpServletResponse response, String errorCode, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\""
                + errorCode
                + "\",\"reason\":\""
                + escapeJson(reason)
                + "\"}}");
    }

    // JSON 문자열 값에 넣을 수 있도록 따옴표·역슬래시·제어 문자를 이스케이프한다.
    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
