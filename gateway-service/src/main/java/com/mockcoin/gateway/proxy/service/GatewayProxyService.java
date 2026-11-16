package com.mockcoin.gateway.proxy.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.mockcoin.gateway.global.config.ServiceRouteProperties;
import com.mockcoin.gateway.global.security.GatewaySecurityHardeningProperties;
import com.mockcoin.gateway.global.security.GatewayTokenAuthenticator;
import com.mockcoin.gateway.global.security.TrustedProxyResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// REST 프록시 실행 로직 (헤더 복사, 내부 user-id 주입, downstream 호출)
@Service
public class GatewayProxyService {
    private static final String INTERNAL_USER_ID_HEADER = "X-Mockcoin-User-Id";
    private static final String CLIENT_ORDER_ID_HEADER = "X-Mockcoin-Client-Order-Id";
    private static final String X_REQUESTED_WITH_HEADER = "X-Requested-With";
    private static final String X_FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String TRACESTATE_HEADER = "tracestate";
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

    private final ServiceRouteProperties routes;
    private final RestClient restClient;
    private final GatewayTokenAuthenticator tokenAuthenticator;
    private final GatewaySecurityHardeningProperties securityProperties;

    // 서비스 라우팅 설정, RestClient, 토큰 인증기, 보안 설정을 주입받는다.
    public GatewayProxyService(
            ServiceRouteProperties routes,
            RestClient restClient,
            GatewayTokenAuthenticator tokenAuthenticator,
            GatewaySecurityHardeningProperties securityProperties
    ) {
        this.routes = routes;
        this.restClient = restClient;
        this.tokenAuthenticator = tokenAuthenticator;
        this.securityProperties = securityProperties;
    }

    // user-service로 요청을 그대로 프록시한다
    public HttpStatusCode proxyToUser(
            HttpMethod method,
            String path,
            HttpServletRequest request,
            HttpServletResponse response,
            boolean forwardRequestBody
    ) {
        HttpHeaders clientHeaders = new HttpHeaders();
        clientHeaders.set(X_FORWARDED_FOR_HEADER, resolveClientIp(request));
        return proxy(method, routes.userService(), path, request, response, forwardRequestBody, clientHeaders, true);
    }

    // market-service로 요청을 그대로 프록시한다(공개 read)
    public HttpStatusCode proxyToMarket(HttpMethod method, String path, HttpServletRequest request, HttpServletResponse response) {
        return proxy(method, routes.marketService(), path, request, response, false, null, false);
    }

    // access token을 검증해 내부 X-Mockcoin-User-Id를 주입하고 trading-service로 프록시한다
    public HttpStatusCode proxyToTrading(
            HttpMethod method,
            String path,
            HttpServletRequest request,
            HttpServletResponse response,
            boolean forwardRequestBody
    ) {
        Object requestUserId = request.getAttribute(GatewayTokenAuthenticator.AUTHENTICATED_USER_ID_REQUEST_ATTRIBUTE);
        Long userId = requestUserId instanceof Long resolvedUserId
                ? resolvedUserId
                : tokenAuthenticator.resolveUserId(
                        request.getHeader(HttpHeaders.AUTHORIZATION),
                        resolveClientIp(request)
                );
        HttpHeaders extraHeaders = new HttpHeaders();
        extraHeaders.set(INTERNAL_USER_ID_HEADER, String.valueOf(userId));
        return proxy(method, routes.tradingService(), path, request, response, forwardRequestBody, extraHeaders, false);
    }

    // 인증 없이 trading-service public 경로(시즌/보드 등)로 프록시한다
    public HttpStatusCode proxyToTradingPublic(HttpMethod method, String path, HttpServletRequest request, HttpServletResponse response) {
        return proxy(method, routes.tradingService(), path, request, response, false, null, false);
    }

    // access token을 검증해 내부 X-Mockcoin-User-Id를 주입하고 community-service로 프록시한다
    public HttpStatusCode proxyToCommunity(
            HttpMethod method,
            String path,
            HttpServletRequest request,
            HttpServletResponse response,
            boolean forwardRequestBody
    ) {
        Object requestUserId = request.getAttribute(GatewayTokenAuthenticator.AUTHENTICATED_USER_ID_REQUEST_ATTRIBUTE);
        Long userId = requestUserId instanceof Long resolvedUserId
                ? resolvedUserId
                : tokenAuthenticator.resolveUserId(
                        request.getHeader(HttpHeaders.AUTHORIZATION),
                        resolveClientIp(request)
                );
        HttpHeaders extraHeaders = new HttpHeaders();
        extraHeaders.set(INTERNAL_USER_ID_HEADER, String.valueOf(userId));
        return proxy(method, routes.communityService(), path, request, response, forwardRequestBody, extraHeaders, false);
    }

    // query string을 유지한 downstream URI로 허용 헤더와 (선택) 본문을 스트리밍 전달하고, 응답 상태·헤더·본문을 그대로 돌려준다.
    private HttpStatusCode proxy(
            HttpMethod method,
            URI baseUri,
            String path,
            HttpServletRequest request,
            HttpServletResponse response,
            boolean forwardRequestBody,
            HttpHeaders extraHeaders,
            boolean forwardAuthorization
    ) {
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path(path)
                .query(request.getQueryString())
                .build(true)
                .toUri();
        RestClient.RequestBodySpec requestSpec = restClient.method(method)
                .uri(uri)
                .headers(headers -> copyHeaders(request, headers, extraHeaders, forwardAuthorization));
        RestClient.RequestHeadersSpec<?> headersSpec;
        if (forwardRequestBody) {
            long contentLength = request.getContentLengthLong();
            if (contentLength >= 0) {
                requestSpec.contentLength(contentLength);
            }
            headersSpec = requestSpec.body(streamRequestBody(request));
        } else {
            headersSpec = requestSpec;
        }

        return headersSpec.exchange((downstreamRequest, downstreamResponse) -> {
            HttpStatusCode status = downstreamResponse.getStatusCode();
            response.setStatus(status.value());
            copyResponseHeaders(downstreamResponse.getHeaders()).forEach((name, values) ->
                    values.forEach(value -> response.addHeader(name, value)));
            if (downstreamResponse.getBody() != null) {
                downstreamResponse.getBody().transferTo(response.getOutputStream());
            }
            return status;
        });
    }

    // 요청 본문을 메모리에 담지 않고 downstream으로 그대로 흘려보내는 Body를 만든다.
    private static StreamingHttpOutputMessage.Body streamRequestBody(HttpServletRequest request) {
        return outputStream -> request.getInputStream().transferTo(outputStream);
    }

    // hop-by-hop 헤더와 Connection에 나열된 헤더를 뺀 downstream 응답 헤더를 복사한다.
    private static HttpHeaders copyResponseHeaders(HttpHeaders downstreamHeaders) {
        HttpHeaders headers = new HttpHeaders();
        Set<String> connectionTokens = responseConnectionTokens(downstreamHeaders);
        downstreamHeaders.forEach((name, values) -> {
            if (!shouldForwardResponseHeader(name, connectionTokens)) {
                return;
            }
            values.forEach(value -> headers.add(name, value));
        });
        return headers;
    }

    // Connection 헤더에 나열된 토큰을 소문자 집합으로 모은다.
    private static Set<String> responseConnectionTokens(HttpHeaders downstreamHeaders) {
        Set<String> tokens = new HashSet<>();
        downstreamHeaders.getOrEmpty(HttpHeaders.CONNECTION).forEach(value ->
                Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(token -> !token.isBlank())
                        .map(token -> token.toLowerCase(Locale.ROOT))
                        .forEach(tokens::add)
        );
        return tokens;
    }

    // hop-by-hop 헤더나 Connection 토큰이 아니어서 클라이언트에 전달할 응답 헤더인지 확인한다.
    private static boolean shouldForwardResponseHeader(String name, Set<String> connectionTokens) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return !HOP_BY_HOP_RESPONSE_HEADERS.contains(normalized) && !connectionTokens.contains(normalized);
    }

    // 허용된 요청 헤더와 추가 헤더를 복사하고, Content-Type이 없으면 JSON으로, Accept-Charset은 UTF-8로 둔다.
    private static void copyHeaders(HttpServletRequest request, HttpHeaders headers, HttpHeaders extraHeaders, boolean forwardAuthorization) {
        Collections.list(request.getHeaderNames()).forEach(name -> {
            if (!shouldForwardInboundHeader(name, forwardAuthorization)) {
                return;
            }
            Collections.list(request.getHeaders(name)).forEach(value -> headers.add(name, value));
        });
        if (extraHeaders != null) {
            headers.addAll(extraHeaders);
        }
        if (!headers.containsHeader(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        headers.setAcceptCharset(java.util.List.of(StandardCharsets.UTF_8));
    }

    // 허용 목록(Content-Type, Accept, X-Requested-With, 주문 ID, trace, 선택적 Authorization)에 든 요청 헤더인지 확인한다.
    // 클라이언트가 보낸 X-Mockcoin-User-Id 등 그 밖의 헤더는 전달하지 않는다.
    private static boolean shouldForwardInboundHeader(String name, boolean forwardAuthorization) {
        return (forwardAuthorization && HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name))
                || HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)
                || HttpHeaders.ACCEPT.equalsIgnoreCase(name)
                || X_REQUESTED_WITH_HEADER.equalsIgnoreCase(name)
                || CLIENT_ORDER_ID_HEADER.equalsIgnoreCase(name)
                || TRACEPARENT_HEADER.equalsIgnoreCase(name)
                || TRACESTATE_HEADER.equalsIgnoreCase(name);
    }

    // 신뢰 프록시 설정을 반영해 실제 클라이언트 IP를 구한다.
    private String resolveClientIp(HttpServletRequest request) {
        return TrustedProxyResolver.resolveClientIp(
                request.getRemoteAddr(),
                request.getHeader(X_FORWARDED_FOR_HEADER),
                securityProperties.getTrustedProxyCidrs()
        );
    }
}
