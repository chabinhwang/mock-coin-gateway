package com.mockcoin.gateway.admin.integration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.global.config.ServiceRouteProperties;
import com.mockcoin.gateway.global.error.GatewayErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

// admin 콘솔 downstream(user/trading/market/ingestor internal admin endpoint) 호출 클라이언트
// admin 토큰 헤더를 포함한 모든 downstream HTTP 호출을 맡고, 집계·머지는 AdminOverviewAggregator가 담당한다.
@Component
public class AdminDownstreamClient {
    private static final String MARKET_TOKEN_HEADER = "X-Mockcoin-Market-Token";
    private static final String INGESTOR_TOKEN_HEADER = "X-Mockcoin-Ingestor-Token";
    public static final String COMMUNITY_TOKEN_HEADER = "X-Mockcoin-Community-Token";
    // hop-by-hop·프레이밍 헤더를 그대로 반환하면 gateway 컨테이너가 자체 Transfer-Encoding을 또 붙여
    // 응답이 malformed(TE 중복)가 되고, Cloudflare 같은 엄격한 프록시가 520으로 거부한다 (RFC 7230 §6.1).
    private static final Set<String> HOP_BY_HOP_RESPONSE_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "content-length"
    );
    private static final Logger log = LoggerFactory.getLogger(AdminDownstreamClient.class);

    private final ServiceRouteProperties routes;
    private final GatewayAdminProperties adminProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // 서비스 라우팅 설정, 관리자 설정, RestClient, ObjectMapper를 주입받는다.
    public AdminDownstreamClient(
            ServiceRouteProperties routes,
            GatewayAdminProperties adminProperties,
            RestClient restClient,
            ObjectMapper objectMapper
    ) {
        this.routes = routes;
        this.adminProperties = adminProperties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    // user-service internal admin endpoint로 요청을 토큰과 함께 프록시한다
    public ResponseEntity<byte[]> user(HttpMethod method, String path, byte[] body) {
        return proxy(method, routes.userService(), path, adminProperties.userServiceToken(), body);
    }

    // trading-service internal admin endpoint로 요청을 토큰과 함께 프록시한다
    public ResponseEntity<byte[]> trading(HttpMethod method, String path, byte[] body) {
        return proxy(method, routes.tradingService(), path, adminProperties.tradingServiceToken(), body);
    }

    // community-service internal admin endpoint로 요청을 community 토큰 헤더와 함께 프록시한다.
    public ResponseEntity<byte[]> community(HttpMethod method, String path, byte[] body) {
        return proxy(
                method,
                routes.communityService(),
                path,
                COMMUNITY_TOKEN_HEADER,
                adminProperties.communityServiceToken(),
                body
        );
    }

    // user-service overview fragment(필수)를 조회한다
    public JsonNode userOverview() {
        return fetchSuccess(routes.userService(), "/internal/user-service/admin/overview", adminProperties.userServiceToken(), "user-service");
    }

    // trading-service overview fragment(필수)를 조회한다
    public JsonNode tradingOverview() {
        return fetchSuccess(routes.tradingService(), "/internal/trading-service/admin/overview", adminProperties.tradingServiceToken(), "trading-service");
    }

    // market-service overview fragment(선택)를 조회한다
    public JsonNode marketOverview() {
        return fetchOptionalSuccess(routes.marketService(), "/internal/market-service/admin/overview", MARKET_TOKEN_HEADER, adminProperties.marketServiceToken(), "market-service");
    }

    // ingestor-service overview fragment(선택)를 조회한다
    public JsonNode ingestorOverview() {
        return fetchOptionalSuccess(routes.ingestorService(), "/internal/ingestor-service/admin/overview", INGESTOR_TOKEN_HEADER, adminProperties.ingestorServiceToken(), "ingestor-service");
    }

    // community-service overview fragment(선택)를 조회한다.
    public JsonNode communityOverview() {
        return fetchOptionalSuccess(
                routes.communityService(),
                "/internal/community-service/admin/overview",
                COMMUNITY_TOKEN_HEADER,
                adminProperties.communityServiceToken(),
                "community-service"
        );
    }

    // admin 콘솔 조회용 user-service GET: success 본문을 반환하고 실패 envelope은 상태·코드를 그대로 전달한다
    public JsonNode userQuery(String pathWithQuery) {
        return query(routes.userService(), pathWithQuery, GatewayAdminProperties.ADMIN_TOKEN_HEADER, adminProperties.userServiceToken(), "user-service");
    }

    // admin 콘솔 조회용 trading-service GET
    public JsonNode tradingQuery(String pathWithQuery) {
        return query(routes.tradingService(), pathWithQuery, GatewayAdminProperties.ADMIN_TOKEN_HEADER, adminProperties.tradingServiceToken(), "trading-service");
    }

    // 서비스 토큰을 붙여 GET하고 success 본문을 반환하며, 토큰 미설정·잘못된 봉투·연결 실패는 gateway 에러로 던진다.
    // downstream 실패 응답은 HTTP 상태·errorCode·reason을 그대로 전달한다.
    private JsonNode query(URI baseUri, String pathWithQuery, String tokenHeader, String serviceToken, String serviceName) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED.exception();
        }
        try {
            byte[] body = restClient.get()
                    .uri(baseUri.resolve(pathWithQuery))
                    .headers(headers -> {
                        headers.set(tokenHeader, serviceToken);
                        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
                    })
                    .retrieve()
                    .body(byte[].class);
            JsonNode envelope = parseJson(body);
            if (envelope == null || !"SUCCESS".equals(envelope.path("resultType").asText()) || envelope.path("success").isMissingNode()) {
                throw GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED.exception(serviceName + " 관리 API 응답이 올바르지 않습니다.");
            }
            return envelope.path("success");
        } catch (RestClientResponseException exception) {
            JsonNode error = parseJson(exception.getResponseBodyAsByteArray());
            JsonNode errorNode = error == null ? null : error.path("error");
            String errorCode = errorNode == null || !errorNode.path("errorCode").isTextual()
                    ? "DOWNSTREAM_ADMIN_FAILED"
                    : errorNode.path("errorCode").asText();
            String reason = errorNode == null || !errorNode.path("reason").isTextual()
                    ? serviceName + " 관리 API 호출에 실패했습니다."
                    : errorNode.path("reason").asText();
            throw GatewayErrorCode.DOWNSTREAM_ADMIN_ERROR.relay(exception.getStatusCode().value(), errorCode, reason);
        } catch (ResourceAccessException exception) {
            throw GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE.exception();
        }
    }

    // 본문이 비었거나 HTML이거나 JSON이 아니면 null을, 아니면 JSON 트리를 반환한다.
    private JsonNode parseJson(byte[] body) {
        if (body == null || body.length == 0 || looksLikeHtml(body)) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // 필수 overview를 조회해 success 본문을 반환하고, 토큰 미설정·실패 상태·잘못된 봉투면 예외를 던진다.
    private JsonNode fetchSuccess(URI baseUri, String path, String serviceToken, String serviceName) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED.exception();
        }
        try {
            JsonNode envelope = restClient.get()
                    .uri(baseUri.resolve(path))
                    .headers(headers -> headers.set(GatewayAdminProperties.ADMIN_TOKEN_HEADER, serviceToken))
                    .retrieve()
                    .body(JsonNode.class);
            if (envelope == null || !"SUCCESS".equals(envelope.path("resultType").asText()) || envelope.path("success").isMissingNode() || envelope.path("success").isNull()) {
                throw GatewayErrorCode.DOWNSTREAM_ADMIN_OVERVIEW_FAILED.exception(serviceName + " overview 응답이 올바르지 않습니다.");
            }
            return envelope.path("success");
        } catch (HttpStatusCodeException exception) {
            throw GatewayErrorCode.DOWNSTREAM_ADMIN_OVERVIEW_FAILED.exception(serviceName + " overview 호출에 실패했습니다: " + exception.getStatusCode());
        }
    }

    // 선택 overview를 조회하고, 주소·토큰이 없거나 호출 실패·잘못된 봉투면 빈 객체를 반환한다.
    private JsonNode fetchOptionalSuccess(URI baseUri, String path, String tokenHeader, String serviceToken, String serviceName) {
        if (baseUri == null || serviceToken == null || serviceToken.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode envelope = restClient.get()
                    .uri(baseUri.resolve(path))
                    .headers(headers -> headers.set(tokenHeader, serviceToken))
                    .retrieve()
                    .body(JsonNode.class);
            if (envelope == null || !"SUCCESS".equals(envelope.path("resultType").asText()) || envelope.path("success").isMissingNode() || envelope.path("success").isNull()) {
                log.warn("Admin overview optional fragment returned non-success envelope service={} path={}", serviceName, path);
                return objectMapper.createObjectNode();
            }
            return envelope.path("success");
        } catch (RuntimeException exception) {
            log.warn("Admin overview optional fragment fetch failed service={} path={} reason={}", serviceName, path, exception.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    // 기본 admin 토큰 헤더(X-Mockcoin-Admin-Token)로 요청을 프록시한다.
    private ResponseEntity<byte[]> proxy(HttpMethod method, URI baseUri, String path, String serviceToken, byte[] body) {
        return proxy(method, baseUri, path, GatewayAdminProperties.ADMIN_TOKEN_HEADER, serviceToken, body);
    }

    // 토큰 헤더를 붙여 요청을 전달하고 hop-by-hop 헤더를 걸러 응답을 반환하며, 실패 응답은 상태·본문·Content-Type을 그대로 돌려준다.
    // HTML 응답이나 빈 실패 본문은 DOWNSTREAM_ADMIN_FAILED, 연결 실패는 DOWNSTREAM_ADMIN_UNAVAILABLE로 던진다.
    private ResponseEntity<byte[]> proxy(
            HttpMethod method,
            URI baseUri,
            String path,
            String tokenHeader,
            String serviceToken,
            byte[] body
    ) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED.exception();
        }
        URI uri = baseUri.resolve(path);
        boolean hasBody = body != null && body.length > 0;
        try {
            RestClient.RequestBodySpec spec = restClient.method(method)
                    .uri(uri)
                    .headers(headers -> {
                        headers.set(tokenHeader, serviceToken);
                        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
                    });
            ResponseEntity<byte[]> response = hasBody
                    ? spec.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(byte[].class)
                    : spec.retrieve().toEntity(byte[].class);
            if (looksLikeHtml(response.getBody())) {
                throw GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED.exception();
            }
            return filterDownstreamHeaders(response);
        } catch (RestClientResponseException exception) {
            byte[] responseBody = exception.getResponseBodyAsByteArray();
            if (looksLikeHtml(responseBody)) {
                throw GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED.relay(exception.getStatusCode().value());
            }
            if (responseBody.length == 0) {
                throw GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED.relay(
                        exception.getStatusCode().value(),
                        GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED.code(),
                        "관리 API가 비어 있는 실패 응답을 반환했습니다."
                );
            }
            HttpHeaders headers = new HttpHeaders();
            MediaType contentType = exception.getResponseHeaders() == null
                    ? null
                    : exception.getResponseHeaders().getContentType();
            if (contentType != null) {
                headers.setContentType(contentType);
            }
            return new ResponseEntity<>(responseBody, headers, exception.getStatusCode());
        } catch (ResourceAccessException exception) {
            throw GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE.exception();
        }
    }

    // downstream 응답 헤더에서 hop-by-hop·프레이밍 헤더를 제거한다.
    // Content-Length/Transfer-Encoding을 버리면 Spring이 byte[] 길이로 Content-Length를 다시 계산한다.
    private static ResponseEntity<byte[]> filterDownstreamHeaders(ResponseEntity<byte[]> response) {
        Set<String> connectionTokens = new HashSet<>();
        response.getHeaders().getOrEmpty(HttpHeaders.CONNECTION).forEach(value ->
                Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(token -> !token.isBlank())
                        .map(token -> token.toLowerCase(Locale.ROOT))
                        .forEach(connectionTokens::add)
        );
        HttpHeaders headers = new HttpHeaders();
        response.getHeaders().forEach((name, values) -> {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP_RESPONSE_HEADERS.contains(normalized) || connectionTokens.contains(normalized)) {
                return;
            }
            values.forEach(value -> headers.add(name, value));
        });
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }

    // Content-Type isCompatibleWith(text/html)는 */* 도 HTML로 오인한다. 본문 prefix만 본다.
    static boolean looksLikeHtml(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        String prefix = new String(body, 0, Math.min(body.length, 64), StandardCharsets.UTF_8).stripLeading();
        if (!prefix.isEmpty() && prefix.charAt(0) == '\uFEFF') {
            prefix = prefix.substring(1).stripLeading();
        }
        return prefix.regionMatches(true, 0, "<!DOCTYPE", 0, 9) || prefix.regionMatches(true, 0, "<html", 0, 5);
    }
}
