package com.mockcoin.gateway.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * admin 콘솔 세션 쿠키 → 권한 부여 경계를 고정한다.
 *
 * <p>admin API는 `ROLE_ADMIN_CONSOLE` 보유 여부로 접근을 판정한다. 이 필터가
 * 무효 세션에 권한을 실어주면 그 뒤에는 아무것도 막아주지 않으므로, 권한이
 * 부여되지 '않아야' 하는 경로를 특히 촘촘하게 고정한다.</p>
 *
 * <p>이 필터는 인증 실패를 거부로 바꾸지 않고 그대로 체인을 진행시킨다. 거부는
 * SecurityFilterChain의 authorize 규칙이 담당하므로, 여기서는 "체인은 항상
 * 진행되지만 권한은 조건부로만 붙는다"가 계약이다.</p>
 */
class GatewayAdminSessionAuthFilterTest {
    private static final String VALID_SESSION = "valid-admin-session-id";
    private static final String ADMIN_ROLE = "ROLE_ADMIN_CONSOLE";

    private final GatewayAdminAuthService authService = mock(GatewayAdminAuthService.class);
    private final RecordingFilterChain chain = new RecordingFilterChain();
    private final GatewayAdminSessionAuthFilter filter = new GatewayAdminSessionAuthFilter(authService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 권한 부여

    @Test
    void grantsAdminConsoleRoleWhenSessionCookieIsValid() throws Exception {
        when(authService.validateSession(VALID_SESSION)).thenReturn(true);

        filter.doFilter(requestWithSessionCookie(VALID_SESSION), new MockHttpServletResponse(), chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo("admin");
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly(ADMIN_ROLE);
        assertThat(chain.invocations()).isEqualTo(1);
    }

    @Test
    void picksFirstCookieWhenSessionCookieIsDuplicated() throws Exception {
        when(authService.validateSession("first")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
                new Cookie(GatewayAdminProperties.SESSION_COOKIE_NAME, "first"),
                new Cookie(GatewayAdminProperties.SESSION_COOKIE_NAME, "second"));

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // 뒤쪽 쿠키를 보지 않고 첫 값으로 판정한다.
        verify(authService).validateSession("first");
        verify(authService, never()).validateSession("second");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    // ---------------------------------------------------------------- 권한 미부여

    @Test
    void leavesContextUnauthenticatedWhenSessionIsRejected() throws Exception {
        when(authService.validateSession(VALID_SESSION)).thenReturn(false);

        filter.doFilter(requestWithSessionCookie(VALID_SESSION), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.invocations()).isEqualTo(1);
    }

    @Test
    void passesNullSessionWhenRequestCarriesNoCookies() throws Exception {
        when(authService.validateSession(null)).thenReturn(false);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        verify(authService).validateSession(null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.invocations()).isEqualTo(1);
    }

    @Test
    void ignoresUnrelatedCookies() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("JSESSIONID", "irrelevant"), new Cookie("OTHER", "value"));

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(authService).validateSession(null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void continuesChainEvenWhenSessionValidationRejects() throws Exception {
        // 거부는 authorize 규칙의 몫이다. 필터가 여기서 응답을 끊으면 permitAll 경로가 깨진다.
        when(authService.validateSession(any())).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestWithSessionCookie("expired"), response, chain);

        assertThat(chain.invocations()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- 쿠키 추출 헬퍼

    @Test
    void extractsSessionCookieValueByName() {
        MockHttpServletRequest request = requestWithSessionCookie(VALID_SESSION);

        assertThat(GatewayAdminSessionAuthFilter.extractSessionCookie(request)).isEqualTo(VALID_SESSION);
    }

    @Test
    void extractsNullWhenNoCookiesArePresent() {
        assertThat(GatewayAdminSessionAuthFilter.extractSessionCookie(new MockHttpServletRequest())).isNull();
    }

    @Test
    void extractsNullWhenSessionCookieIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("JSESSIONID", "irrelevant"));

        assertThat(GatewayAdminSessionAuthFilter.extractSessionCookie(request)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void extractsBlankCookieValueAsIsWithoutNormalizing(String cookieValue) {
        // 빈 값 판단은 authService.validateSession 쪽 책임이라 추출 단계에서 걸러내지 않는다.
        MockHttpServletRequest request = requestWithSessionCookie(cookieValue);

        assertThat(GatewayAdminSessionAuthFilter.extractSessionCookie(request)).isEqualTo(cookieValue);
    }

    @Test
    void extractsNullWhenSessionCookieValueIsNull() {
        // Optional.map(Cookie::getValue)가 null을 만나면 empty로 접혀 결과도 null이 된다.
        MockHttpServletRequest request = requestWithSessionCookie(null);

        assertThat(GatewayAdminSessionAuthFilter.extractSessionCookie(request)).isNull();
    }

    // ---------------------------------------------------------------- 픽스처

    private static MockHttpServletRequest requestWithSessionCookie(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(GatewayAdminProperties.SESSION_COOKIE_NAME, value));
        return request;
    }

    /** 체인 진행 횟수를 세는 FilterChain 테스트 더블. */
    private static final class RecordingFilterChain implements FilterChain {
        private int invocations;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
            invocations++;
        }

        int invocations() {
            return invocations;
        }
    }
}
