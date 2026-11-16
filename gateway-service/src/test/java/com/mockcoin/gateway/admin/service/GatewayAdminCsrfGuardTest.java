package com.mockcoin.gateway.admin.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.shared.api.ApiException;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 쿠키 인증 admin 요청의 CSRF(same-origin) 방어를 고정한다.
 *
 * <p>admin 세션은 쿠키로 유지되므로, 브라우저가 자동으로 쿠키를 실어 보내는
 * cross-site 요청을 막지 않으면 관리자 권한 상태 변경이 외부 페이지에서 유발될 수
 * 있다. 이 guard가 그 유일한 방어선이라 "우회 가능한 조합이 없는지"를 중심으로
 * 검증한다.</p>
 *
 * <p>반대로 과잉 차단도 장애다. gateway는 프록시(ingress) 뒤에서 동작하므로
 * `X-Forwarded-*`로 재구성한 origin이 정상 요청을 401로 만들지 않아야 한다.</p>
 */
class GatewayAdminCsrfGuardTest {
    private static final String ORIGIN_VALIDATION_FAILED = "admin origin validation failed";
    private static final String HEADER_TOKEN = "valid-admin-token";

    private final GatewayAdminFacadeService facadeService = mock(GatewayAdminFacadeService.class);
    private final GatewayAdminCsrfGuard guard = new GatewayAdminCsrfGuard(facadeService);

    // ---------------------------------------------------------------- 면제 경로

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "TRACE"})
    void skipsValidationForSafeMethods(String method) {
        // safe method는 상태를 바꾸지 않으므로 Origin이 아예 없어도 통과시킨다.
        MockHttpServletRequest request = cookieRequest(method, null);

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void skipsValidationWhenHeaderTokenIsValid(String method) {
        // header token 경로는 브라우저가 자동으로 실어보내지 않으므로 CSRF 대상이 아니다.
        when(facadeService.isGatewayAdminTokenValid(HEADER_TOKEN)).thenReturn(true);
        MockHttpServletRequest request = cookieRequest(method, "https://evil.example.com");

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, HEADER_TOKEN)).doesNotThrowAnyException();
    }

    @Test
    void skipsValidationWhenRequestCarriesNoSessionCookie() {
        // 쿠키가 없으면 브라우저 자동 인증이 성립하지 않는다.
        MockHttpServletRequest request = unsafeRequest();
        request.addHeader("Origin", "https://evil.example.com");

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 차단 경로

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void rejectsWhenOriginAndRefererAreBothMissingOrBlank(String headerValue) {
        MockHttpServletRequest request = cookieRequest("POST", headerValue);
        if (headerValue != null) {
            request.addHeader("Referer", headerValue);
        }

        assertThatThrownBy(() -> guard.verifyUnsafeCookieRequest(request, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(ORIGIN_VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example.com",           // host 불일치
            "http://admin.example.com",           // scheme 불일치
            "https://admin.example.com:8443",     // port 불일치
            "https://sub.admin.example.com",      // 하위 도메인은 다른 origin
            "not a valid uri",                    // URI.create 실패
            "https://",                           // host 없음
    })
    void rejectsCrossOriginOrUnparsableSources(String origin) {
        MockHttpServletRequest request = cookieRequest("POST", origin);
        request.setScheme("https");
        request.setServerName("admin.example.com");
        request.setServerPort(443);

        assertThatThrownBy(() -> guard.verifyUnsafeCookieRequest(request, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(ORIGIN_VALIDATION_FAILED);
    }

    @Test
    void rejectsWhenHeaderTokenIsPresentButInvalid() {
        when(facadeService.isGatewayAdminTokenValid(any())).thenReturn(false);
        MockHttpServletRequest request = cookieRequest("POST", "https://evil.example.com");
        request.setScheme("https");
        request.setServerName("admin.example.com");
        request.setServerPort(443);

        assertThatThrownBy(() -> guard.verifyUnsafeCookieRequest(request, "wrong-token"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(ORIGIN_VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- 정상 same-origin

    @Test
    void acceptsSameOriginRequest() {
        MockHttpServletRequest request = cookieRequest("POST", "https://admin.example.com");
        request.setScheme("https");
        request.setServerName("admin.example.com");
        request.setServerPort(443);

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    @Test
    void fallsBackToRefererWhenOriginIsAbsent() {
        MockHttpServletRequest request = unsafeRequest();
        request.setCookies(new Cookie(GatewayAdminProperties.SESSION_COOKIE_NAME, "session"));
        request.addHeader("Referer", "https://admin.example.com/admin/dashboard");
        request.setScheme("https");
        request.setServerName("admin.example.com");
        request.setServerPort(443);

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    @Test
    void prefersOriginOverRefererWhenBothArePresent() {
        MockHttpServletRequest request = cookieRequest("POST", "https://evil.example.com");
        request.addHeader("Referer", "https://admin.example.com/admin");
        request.setScheme("https");
        request.setServerName("admin.example.com");
        request.setServerPort(443);

        // Origin이 있으면 Referer로 우회할 수 없어야 한다.
        assertThatThrownBy(() -> guard.verifyUnsafeCookieRequest(request, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void ignoresBlankOriginAndUsesRefererInstead() {
        MockHttpServletRequest request = unsafeRequest();
        request.setCookies(new Cookie(GatewayAdminProperties.SESSION_COOKIE_NAME, "session"));
        request.addHeader("Origin", "   ");
        request.addHeader("Referer", "https://admin.example.com/admin");
        request.setScheme("https");
        request.setServerName("admin.example.com");
        request.setServerPort(443);

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "HTTPS://admin.example.com, https",
            "https://ADMIN.example.com, https",
    })
    void comparesSchemeAndHostCaseInsensitively(String origin, String requestScheme) {
        MockHttpServletRequest request = cookieRequest("POST", origin);
        request.setScheme(requestScheme);
        request.setServerName("admin.example.com");
        request.setServerPort(443);

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 기본 포트 정규화

    @ParameterizedTest
    @CsvSource({
            "https://admin.example.com, https, 443",
            "http://admin.example.com, http, 80",
    })
    void treatsOmittedOriginPortAsSchemeDefault(String origin, String scheme, int requestPort) {
        MockHttpServletRequest request = cookieRequest("POST", origin);
        request.setScheme(scheme);
        request.setServerName("admin.example.com");
        request.setServerPort(requestPort);

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "https://admin.example.com, https, 443",
            "http://admin.example.com, http, 80",
    })
    void treatsNonPositiveRequestPortAsSchemeDefault(String origin, String scheme, int ignoredExpectedPort) {
        MockHttpServletRequest request = cookieRequest("POST", origin);
        request.setScheme(scheme);
        request.setServerName("admin.example.com");
        request.setServerPort(0);

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 프록시 헤더

    @Test
    void rebuildsOriginFromForwardedHeaders() {
        // ingress 뒤에서는 요청 자체가 http/8080이라 forwarded 헤더 없이는 정상 요청도 막힌다.
        MockHttpServletRequest request = cookieRequest("POST", "https://admin.example.com");
        request.setScheme("http");
        request.setServerName("10.0.0.5");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "admin.example.com");
        request.addHeader("X-Forwarded-Port", "443");

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    @Test
    void usesFirstValueOfCommaSeparatedForwardedHeaders() {
        MockHttpServletRequest request = cookieRequest("POST", "https://admin.example.com");
        request.setScheme("http");
        request.setServerName("10.0.0.5");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Proto", "https, http");
        request.addHeader("X-Forwarded-Host", "admin.example.com, internal.example.com");
        request.addHeader("X-Forwarded-Port", "443, 8080");

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    @Test
    void stripsPortSuffixFromForwardedHost() {
        MockHttpServletRequest request = cookieRequest("POST", "https://admin.example.com");
        request.setScheme("http");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "admin.example.com:443");
        request.addHeader("X-Forwarded-Port", "443");

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    @Test
    void fallsBackToRequestPortWhenForwardedPortIsNotNumeric() {
        MockHttpServletRequest request = cookieRequest("POST", "https://admin.example.com:8443");
        request.setScheme("http");
        request.setServerPort(8443);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "admin.example.com");
        request.addHeader("X-Forwarded-Port", "not-a-number");

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void ignoresBlankForwardedHeadersAndUsesRequestValues(String blank) {
        MockHttpServletRequest request = cookieRequest("POST", "https://admin.example.com");
        request.setScheme("https");
        request.setServerName("admin.example.com");
        request.setServerPort(443);
        request.addHeader("X-Forwarded-Proto", blank);
        request.addHeader("X-Forwarded-Host", blank);
        request.addHeader("X-Forwarded-Port", blank);

        assertThatCode(() -> guard.verifyUnsafeCookieRequest(request, null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenForwardedHostDoesNotMatchOrigin() {
        // 스푸핑된 Origin이 forwarded host와 어긋나면 막아야 한다.
        MockHttpServletRequest request = cookieRequest("POST", "https://evil.example.com");
        request.setScheme("http");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "admin.example.com");
        request.addHeader("X-Forwarded-Port", "443");

        assertThatThrownBy(() -> guard.verifyUnsafeCookieRequest(request, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(ORIGIN_VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- 픽스처

    private static MockHttpServletRequest unsafeRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        return request;
    }

    /** 세션 쿠키를 가진 요청. origin이 null이면 Origin 헤더를 붙이지 않는다. */
    private static MockHttpServletRequest cookieRequest(String method, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setCookies(new Cookie(GatewayAdminProperties.SESSION_COOKIE_NAME, "session-id"));
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }
}
