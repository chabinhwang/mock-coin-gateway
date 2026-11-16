package com.mockcoin.gateway.admin.config;

import com.mockcoin.gateway.admin.service.GatewayAdminAuthService;
import com.mockcoin.gateway.admin.service.GatewayAdminSessionAuthFilter;
import com.mockcoin.gateway.global.security.GatewayRateLimitingFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// admin 콘솔(/admin/**)용 SecurityFilterChain 및 세션 인증 필터 설정
@Configuration
@ConditionalOnProperty(prefix = "mockcoin.admin", name = "enabled", havingValue = "true")
public class GatewayAdminSecurityConfig {
    // admin 콘솔 경로에 stateless 세션, 보안 헤더, 세션 인증 필터를 적용한다
    @Bean
    @Order(1)
    SecurityFilterChain gatewayAdminSecurityFilterChain(
            HttpSecurity http,
            GatewayAdminProperties properties,
            GatewayAdminAuthService authService,
            GatewayRateLimitingFilter rateLimitingFilter
    ) throws Exception {
        String basePath = properties.basePath();
        GatewayAdminSessionAuthFilter adminSessionAuthFilter = new GatewayAdminSessionAuthFilter(authService);
        return http
                .securityMatcher(basePath + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000).includeSubDomains(true))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
                .addFilterBefore(adminSessionAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitingFilter, GatewayAdminSessionAuthFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                basePath,
                                basePath + "/",
                                basePath + "/login.html",
                                basePath + "/login.js",
                                basePath + "/styles.css",
                                basePath + "/favicon.png",
                                basePath + "/favicon.ico",
                                basePath + "/api/auth/**",
                                basePath + "/api/v1/admin-console/**")
                        .permitAll()
                        .requestMatchers(basePath + "/**").authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    if (request.getRequestURI() != null && request.getRequestURI().contains("/api/")) {
                        response.setStatus(401);
                        response.setCharacterEncoding("UTF-8");
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write(
                                "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"UNAUTHORIZED\",\"reason\":\"인증이 필요합니다.\"}}"
                        );
                        return;
                    }
                    response.sendRedirect(basePath + "/login.html");
                }))
                .build();
    }
}
