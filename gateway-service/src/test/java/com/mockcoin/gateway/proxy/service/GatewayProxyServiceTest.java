package com.mockcoin.gateway.proxy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.mockcoin.gateway.global.config.ServiceRouteProperties;
import com.mockcoin.gateway.global.security.GatewaySecurityHardeningProperties;
import com.mockcoin.gateway.global.security.GatewayTokenAuthenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;

class GatewayProxyServiceTest {
    private HttpServer tradingServer;
    private AtomicReference<String> observedUserId;
    private AtomicReference<String> observedBody;
    private AtomicReference<List<String>> observedContentLength;
    private AtomicReference<List<String>> observedTransferEncoding;

    @BeforeEach
    void setUp() throws IOException {
        observedUserId = new AtomicReference<>();
        observedBody = new AtomicReference<>();
        observedContentLength = new AtomicReference<>(List.of());
        observedTransferEncoding = new AtomicReference<>(List.of());
        tradingServer = HttpServer.create(new InetSocketAddress(0), 0);
        tradingServer.createContext("/api/v1/portfolio", this::handlePortfolio);
        tradingServer.start();
    }

    @AfterEach
    void tearDown() {
        tradingServer.stop(0);
    }

    @Test
    void reusesAffirmativeAuthenticationStoredOnTheRequest() {
        GatewayTokenAuthenticator authenticator = mock(GatewayTokenAuthenticator.class);
        ServiceRouteProperties routes = new ServiceRouteProperties(
                URI.create("http://user.invalid"),
                URI.create("http://127.0.0.1:" + tradingServer.getAddress().getPort()),
                URI.create("http://market.invalid"),
                URI.create("http://ingestor.invalid"),
                URI.create("http://community.invalid")
        );
        GatewayProxyService service = new GatewayProxyService(
                routes,
                RestClient.builder().build(),
                authenticator,
                new GatewaySecurityHardeningProperties()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portfolio");
        request.addHeader("Authorization", "Bearer valid-token");
        request.setAttribute("com.mockcoin.gateway.authenticatedUserId", 777L);

        service.proxyToTrading(
                HttpMethod.GET, "/api/v1/portfolio", request, new MockHttpServletResponse(), false);

        assertThat(observedUserId.get()).isEqualTo("777");
        verifyNoInteractions(authenticator);
    }

    @Test
    void fallsBackToFullAuthenticationWithResolvedClientIpWhenRequestAttributeIsAbsent() {
        GatewayTokenAuthenticator authenticator = mock(GatewayTokenAuthenticator.class);
        when(authenticator.resolveUserId("Bearer valid-token", "198.51.100.1")).thenReturn(888L);
        ServiceRouteProperties routes = new ServiceRouteProperties(
                URI.create("http://user.invalid"),
                URI.create("http://127.0.0.1:" + tradingServer.getAddress().getPort()),
                URI.create("http://market.invalid"),
                URI.create("http://ingestor.invalid"),
                URI.create("http://community.invalid")
        );
        GatewaySecurityHardeningProperties securityProperties = new GatewaySecurityHardeningProperties();
        securityProperties.setTrustedProxyCidrs(java.util.List.of("127.0.0.1/32"));
        GatewayProxyService service = new GatewayProxyService(
                routes,
                RestClient.builder().build(),
                authenticator,
                securityProperties
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portfolio");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.55, 198.51.100.1");
        request.addHeader("Authorization", "Bearer valid-token");

        service.proxyToTrading(
                HttpMethod.GET, "/api/v1/portfolio", request, new MockHttpServletResponse(), false);

        assertThat(observedUserId.get()).isEqualTo("888");
        verify(authenticator).resolveUserId("Bearer valid-token", "198.51.100.1");
    }

    @Test
    void downstreamConnectionFailureDoesNotCommitResponse() {
        ServiceRouteProperties routes = new ServiceRouteProperties(
                URI.create("http://user.invalid"),
                URI.create("http://trading.invalid"),
                URI.create("http://127.0.0.1:1"),
                URI.create("http://ingestor.invalid"),
                URI.create("http://community.invalid")
        );
        GatewayProxyService service = new GatewayProxyService(
                routes,
                RestClient.builder().build(),
                mock(GatewayTokenAuthenticator.class),
                new GatewaySecurityHardeningProperties()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/markets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> service.proxyToMarket(HttpMethod.GET, "/api/v1/markets", request, response))
                .isInstanceOf(RuntimeException.class);

        assertThat(response.isCommitted()).isFalse();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void forwardsUnknownLengthRequestBodyWithoutTransferEncodingHint() {
        GatewayTokenAuthenticator authenticator = mock(GatewayTokenAuthenticator.class);
        ServiceRouteProperties routes = routesForTradingServer();
        GatewayProxyService service = new GatewayProxyService(
                routes,
                RestClient.builder().build(),
                authenticator,
                new GatewaySecurityHardeningProperties()
        );
        byte[] body = "http2-unknown-length-body".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/portfolio") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.setContent(body);
        request.setAttribute("com.mockcoin.gateway.authenticatedUserId", 777L);

        service.proxyToTrading(
                HttpMethod.POST, "/api/v1/portfolio", request, new MockHttpServletResponse(), true);

        assertThat(observedBody.get()).isEqualTo("http2-unknown-length-body");
        assertThat(observedContentLength.get()).isNullOrEmpty();
    }

    @Test
    void preservesKnownZeroLengthForBodyForwardingEndpoint() {
        GatewayProxyService service = new GatewayProxyService(
                routesForTradingServer(),
                RestClient.builder().build(),
                mock(GatewayTokenAuthenticator.class),
                new GatewaySecurityHardeningProperties()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/portfolio");
        request.setContent(new byte[0]);
        request.setAttribute("com.mockcoin.gateway.authenticatedUserId", 777L);

        service.proxyToTrading(
                HttpMethod.POST, "/api/v1/portfolio", request, new MockHttpServletResponse(), true);

        assertThat(observedBody.get()).isEmpty();
        assertThat(observedContentLength.get()).containsExactly("0");
        assertThat(observedTransferEncoding.get()).isNullOrEmpty();
    }

    private ServiceRouteProperties routesForTradingServer() {
        return new ServiceRouteProperties(
                URI.create("http://user.invalid"),
                URI.create("http://127.0.0.1:" + tradingServer.getAddress().getPort()),
                URI.create("http://market.invalid"),
                URI.create("http://ingestor.invalid"),
                URI.create("http://community.invalid")
        );
    }

    private void handlePortfolio(HttpExchange exchange) throws IOException {
        observedUserId.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-User-Id"));
        observedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        observedContentLength.set(exchange.getRequestHeaders().get("Content-Length"));
        observedTransferEncoding.set(exchange.getRequestHeaders().get("Transfer-Encoding"));
        byte[] body = "{\"resultType\":\"SUCCESS\",\"success\":{},\"error\":null}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }
}
