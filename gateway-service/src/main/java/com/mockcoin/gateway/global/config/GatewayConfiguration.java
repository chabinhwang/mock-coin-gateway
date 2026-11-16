package com.mockcoin.gateway.global.config;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.config.GatewayAdminRedisProperties;
import com.mockcoin.gateway.global.security.GatewayRateLimitingFilter;
import com.mockcoin.gateway.global.security.GatewaySecurityHardeningProperties;
import com.mockcoin.gateway.websocket.config.GatewayRedisMarketEventProperties;
import com.mockcoin.gateway.websocket.config.GatewayRedisPrivateEventProperties;
import com.mockcoin.gateway.websocket.config.GatewayWebSocketProperties;
import com.mockcoin.shared.api.ApiResponse;
import com.mockcoin.shared.api.CommonErrorCode;
import com.mockcoin.shared.api.ErrorCode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;

// gateway 공용 RestClient, /api/** SecurityFilterChain, CORS, 에러 응답 빈을 정의한다
@Configuration
@EnableConfigurationProperties({ServiceRouteProperties.class, GatewayWebSocketProperties.class, GatewayAdminProperties.class, GatewayAdminRedisProperties.class, GatewayRedisMarketEventProperties.class, GatewayRedisPrivateEventProperties.class, GatewayHttpProperties.class, GatewaySecurityHardeningProperties.class, GatewayAuthProperties.class})
public class GatewayConfiguration {
    // downstream 서비스 호출에 사용하는 connection-pooled RestClient를 생성한다
    @Bean
    RestClient restClient(RestClient.Builder builder, GatewayHttpProperties properties) {
        // JDK HttpClient는 기본으로 커넥션을 풀링·재사용한다(HTTP/1.1 keep-alive + HTTP/2 멀티플렉싱).
        // SimpleClientHttpRequestFactory는 명시적 풀링 없이 URLConnection을 쓰므로, downstream 서비스 커넥션을 계속 살려 두려고 여기서 교체한다.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.requestFactory(requestFactory).build();
    }

    // /api/** 및 /actuator/** 경로에 보안 헤더, CORS, rate limit, stateless 세션을 적용한다
    @Bean
    @Order(2)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            GatewaySecurityHardeningProperties securityProperties,
            ObjectMapper objectMapper,
            GatewayRateLimitingFilter rateLimitingFilter
    ) throws Exception {
        http
                .securityMatcher("/api/**", "/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource(securityProperties)))
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterAfter(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeFailResponse(objectMapper, response, CommonErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeFailResponse(objectMapper, response, CommonErrorCode.FORBIDDEN)));
        // Spring Security 7에서 채널 보안(requiresChannel)이 제거돼 redirectToHttps로 대체한다(requireSsl일 때만 HTTPS 강제).
        if (securityProperties.isRequireSsl()) {
            http.redirectToHttps(org.springframework.security.config.Customizer.withDefaults());
        }
        return http.build();
    }

    // /api/** 외 경로에 대한 fallback security chain (모든 요청 허용)
    @Bean
    @Order(3)
    SecurityFilterChain fallbackSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                // Spring Security는 CORS 설정 빈이 있으면 모든 체인에 CORS를 자동 적용한다. 그러면 /ws 핸드셰이크가
                // REST CORS 허용 목록으로 다시 검사돼 RN 미니앱 Origin(https://<API 호스트>)이 403으로 막힌다.
                // /ws의 origin은 mockcoin.websocket.allowed-origin-patterns가 검사한다.
                .cors(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    // 허용 origin/method/header 기반 CORS 설정을 생성한다
    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewaySecurityHardeningProperties securityProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(securityProperties.getCorsAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "X-Requested-With",
                "X-Mockcoin-Client-Order-Id",
                "traceparent",
                "tracestate"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // 에러 코드의 HTTP 상태와 실패 봉투 JSON을 응답에 쓴다.
    private static void writeFailResponse(ObjectMapper objectMapper, HttpServletResponse response, ErrorCode error)
            throws IOException {
        response.setStatus(error.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(error));
    }
}
