package com.mockcoin.gateway.global.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.global.config.ServiceRouteProperties;
import com.mockcoin.gateway.global.error.GatewayErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

// 토큰 검증의 authoritative 경로로 user-service /api/v1/auth/me를 호출하는 클라이언트
@Component
public class GatewayRemoteAuthClient {
    private static final Logger log = LoggerFactory.getLogger(GatewayRemoteAuthClient.class);

    private final ServiceRouteProperties routes;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // 서비스 라우팅 설정, RestClient, ObjectMapper를 주입받는다.
    public GatewayRemoteAuthClient(
            ServiceRouteProperties routes,
            RestClient restClient,
            ObjectMapper objectMapper
    ) {
        this.routes = routes;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    // user-service /api/v1/auth/me로 토큰을 검증해 userId를 반환한다(클라이언트 IP는 X-Forwarded-For로 전달).
    // 401/403은 토큰 거부, 그 밖의 호출 실패는 인증 서비스 장애, userId가 없는 응답은 잘못된 응답 예외로 던진다.
    Long resolveUserId(String authorization, String clientIp) {
        byte[] response;
        try {
            response = restClient.get()
                    .uri(routes.userService().resolve("/api/v1/auth/me"))
                    .headers(headers -> {
                        headers.set(HttpHeaders.AUTHORIZATION, authorization);
                        if (clientIp != null && !clientIp.isBlank() && !"unknown".equalsIgnoreCase(clientIp)) {
                            headers.set("X-Forwarded-For", clientIp);
                        }
                    })
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw GatewayErrorCode.ACCESS_TOKEN_REJECTED.exception();
            }
            log.warn("Remote authentication service rejected verification with status={}", status);
            throw GatewayErrorCode.AUTH_SERVICE_UNAVAILABLE.exception();
        } catch (RuntimeException exception) {
            log.warn("Remote authentication service verification failed", exception);
            throw GatewayErrorCode.AUTH_SERVICE_UNAVAILABLE.exception();
        }

        try {
            if (response == null || response.length == 0) {
                throw new IllegalArgumentException("empty authentication response");
            }
            JsonNode userId = objectMapper.readTree(response).path("success").path("userId");
            if (!userId.canConvertToLong()) {
                throw new IllegalArgumentException("missing authentication user id");
            }
            return userId.asLong();
        } catch (Exception exception) {
            log.warn("Remote authentication service returned an invalid success response", exception);
            throw GatewayErrorCode.AUTH_SERVICE_INVALID_RESPONSE.exception();
        }
    }
}
