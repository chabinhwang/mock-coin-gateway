package com.mockcoin.gateway.admin.service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// admin 세션 쿠키를 읽어 유효하면 SecurityContext에 ROLE_ADMIN_CONSOLE 권한을 부여한다
public class GatewayAdminSessionAuthFilter extends OncePerRequestFilter {
    private final GatewayAdminAuthService authService;

    // 세션 검증에 쓸 관리자 인증 서비스를 받는다.
    public GatewayAdminSessionAuthFilter(GatewayAdminAuthService authService) {
        this.authService = authService;
    }

    // 세션 쿠키 검증 후 인증된 Authentication 객체를 컨텍스트에 채운다
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String sessionId = extractSessionCookie(request);
        if (authService.validateSession(sessionId)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "admin",
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN_CONSOLE"))));
        }
        filterChain.doFilter(request, response);
    }

    // 요청 쿠키에서 ADMIN_SESSION 값을 추출한다
    public static String extractSessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> GatewayAdminProperties.SESSION_COOKIE_NAME.equals(cookie.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
    }
}
