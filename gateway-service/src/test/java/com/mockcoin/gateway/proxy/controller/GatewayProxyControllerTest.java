package com.mockcoin.gateway.proxy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayProxyControllerTest {
    private static final TestBackends BACKENDS = TestBackends.start();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerRoutes(DynamicPropertyRegistry registry) {
        registry.add("mockcoin.routes.user-service", () -> BACKENDS.userUri().toString());
        registry.add("mockcoin.routes.trading-service", () -> BACKENDS.tradingUri().toString());
        registry.add("mockcoin.routes.market-service", () -> BACKENDS.marketUri().toString());
        registry.add("mockcoin.security.trusted-proxy-cidrs", () -> "127.0.0.1/32");
    }

    @AfterAll
    static void stopBackends() {
        BACKENDS.stop();
    }

    @Test
    void proxiesMarketReadWithQueryString() throws Exception {
        mockMvc.perform(get("/api/v1/markets?foo=bar"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"resultType\":\"SUCCESS\",\"success\":{\"query\":\"foo=bar\"},\"error\":null}"));
    }

    @Test
    void forwardsOnlyAllowedHeadersToPublicDownstreamServices() throws Exception {
        BACKENDS.lastMarketAdminToken.set(null);
        BACKENDS.lastMarketInternalEventToken.set(null);
        BACKENDS.lastMarketIngestorToken.set(null);
        BACKENDS.lastMarketToken.set(null);
        BACKENDS.lastMarketAuthorization.set(null);
        BACKENDS.lastMarketTraceparent.set(null);
        BACKENDS.lastMarketUnknownHeader.set(null);

        mockMvc.perform(get("/api/v1/markets")
                        .header("Authorization", "Bearer leaked-public-token")
                        .header("X-Mockcoin-Admin-Token", "admin-token")
                        .header("X-Mockcoin-Internal-Event-Token", "event-token")
                        .header("X-Mockcoin-Ingestor-Token", "ingestor-token")
                        .header("X-Mockcoin-Market-Token", "market-token")
                        .header("X-Unlisted-Header", "unlisted")
                        .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastMarketAdminToken.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastMarketInternalEventToken.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastMarketIngestorToken.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastMarketToken.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastMarketAuthorization.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastMarketUnknownHeader.get()).isNull();
        // gateway가 트레이스 참여자가 된 뒤로는 들어온 traceparent를 그대로 통과시키지 않는다.
        // trace id는 이어받아 상관관계를 유지하고, span id는 gateway 자신의 것으로 교체한다
        // (W3C trace context). downstream은 gateway를 부모 span으로 보게 된다.
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastMarketTraceparent.get())
                .startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-")
                .isNotEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");
    }

    @Test
    void rejectsTradingRequestWithoutAuthorization() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void injectsAuthenticatedUserIdAndStripsClientSpoofedHeader() throws Exception {
        BACKENDS.lastTradingUserId.set(null);
        BACKENDS.lastTradingSpoofHeaders.set(List.of());

        mockMvc.perform(get("/api/v1/portfolio")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Mockcoin-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.observedUserId").value("777"));

        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingUserId.get()).isEqualTo("777");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingSpoofHeaders.get()).containsExactly("777");
    }

    @Test
    void proxiesUserBillingAndResetRoutesWithBodyAndQuery() throws Exception {
        BACKENDS.lastUserPath.set(null);
        BACKENDS.lastUserMethod.set(null);
        BACKENDS.lastUserBody.set(null);
        BACKENDS.lastUserAuthorization.set(null);

        mockMvc.perform(post("/api/v1/billing/iap/verify?dryRun=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer user-token")
                        .content("{\"orderId\":\"iap-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.path").value("/api/v1/billing/iap/verify"))
                .andExpect(jsonPath("$.success.query").value("dryRun=true"));

        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserMethod.get()).isEqualTo("POST");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserBody.get()).isEqualTo("{\"orderId\":\"iap-1\"}");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserAuthorization.get()).isEqualTo("Bearer user-token");

        mockMvc.perform(get("/api/v1/me/reset-pass-purchases?cursor=abc")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.path").value("/api/v1/me/reset-pass-purchases"))
                .andExpect(jsonPath("$.success.query").value("cursor=abc"));
    }

    @Test
    void preservesDownstreamStatusContentTypeCustomHeaderAndBody() throws Exception {
        mockMvc.perform(get("/api/v1/billing/custom-header"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Downstream-Trace", "trace-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"resultType\":\"SUCCESS\",\"success\":{\"streamed\":true},\"error\":null}"));
    }

    @Test
    void forwardsFirstUntrustedClientIpFromRightSideOfProxyChain() throws Exception {
        BACKENDS.lastUserForwardedFor.set(List.of());

        mockMvc.perform(get("/api/v1/me/profile")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .header("X-Forwarded-For", "203.0.113.10, 198.51.100.20")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserForwardedFor.get())
                .containsExactly("198.51.100.20");
    }

    @Test
    void ignoresSpoofedForwardedForFromUntrustedRemote() throws Exception {
        BACKENDS.lastUserForwardedFor.set(List.of());

        mockMvc.perform(get("/api/v1/me/profile")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.77");
                            return request;
                        })
                        .header("X-Forwarded-For", "203.0.113.250")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserForwardedFor.get())
                .containsExactly("198.51.100.77");
    }

    @Test
    void stripsHopByHopHeadersFromDownstreamChunkedResponses() throws Exception {
        mockMvc.perform(get("/api/v1/billing/chunked"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Transfer-Encoding"))
                .andExpect(header().doesNotExist("X-Connection-Secret"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"resultType\":\"SUCCESS\",\"success\":{\"chunked\":true},\"error\":null}"));
    }

    @Test
    void proxiesNicknameValidationErrorEnvelopeFromUserService() throws Exception {
        BACKENDS.lastUserPath.set(null);
        BACKENDS.lastUserMethod.set(null);
        BACKENDS.lastUserBody.set(null);
        BACKENDS.lastUserAuthorization.set(null);

        mockMvc.perform(post("/api/v1/me/profile/nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer user-token")
                        .content("{\"nickname\":\"badword-user\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("NICKNAME_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.reason").value("부적절한 단어가 포함된 닉네임은 사용할 수 없습니다."));

        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserPath.get()).isEqualTo("/api/v1/me/profile/nickname");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserMethod.get()).isEqualTo("POST");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserBody.get()).isEqualTo("{\"nickname\":\"badword-user\"}");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserAuthorization.get()).isEqualTo("Bearer user-token");
    }

    @Test
    void proxiesNotificationAgreementsToUserService() throws Exception {
        BACKENDS.lastUserPath.set(null);
        BACKENDS.lastUserMethod.set(null);
        BACKENDS.lastUserBody.set(null);

        mockMvc.perform(get("/api/v1/me/notification-agreements")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserPath.get()).isEqualTo("/api/v1/me/notification-agreements");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserMethod.get()).isEqualTo("GET");

        mockMvc.perform(put("/api/v1/me/notification-agreements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer user-token")
                        .content("{\"templateCode\":\"mock-coin-reply-alert\",\"status\":\"agreed\"}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserPath.get()).isEqualTo("/api/v1/me/notification-agreements");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserMethod.get()).isEqualTo("PUT");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserBody.get())
                .isEqualTo("{\"templateCode\":\"mock-coin-reply-alert\",\"status\":\"agreed\"}");
    }

    @Test
    void proxiesOrderRoutesWithClientOrderIdAndAuthenticatedUser() throws Exception {
        BACKENDS.lastTradingPath.set(null);
        BACKENDS.lastTradingMethod.set(null);
        BACKENDS.lastTradingBody.set(null);
        BACKENDS.lastTradingClientOrderId.set(null);

        String requestBody = "{\"market\":\"KRW-BTC\"}";
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Mockcoin-Client-Order-Id", "client-123")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.path").value("/api/v1/orders"))
                .andExpect(jsonPath("$.success.observedUserId").value("777"));

        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingMethod.get()).isEqualTo("POST");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingBody.get()).isEqualTo(requestBody);
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingClientOrderId.get()).isEqualTo("client-123");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingContentLengthHeaders.get())
                .containsExactly(String.valueOf(requestBody.getBytes(StandardCharsets.UTF_8).length));
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingTransferEncodingHeaders.get()).isNullOrEmpty();
    }

    @Test
    void proxiesBodylessCancelWithoutForwardingBodyLengthHeaders() throws Exception {
        BACKENDS.lastTradingPath.set(null);
        BACKENDS.lastTradingMethod.set(null);
        BACKENDS.lastTradingBody.set(null);
        BACKENDS.lastTradingContentLengthHeaders.set(List.of());
        BACKENDS.lastTradingTransferEncodingHeaders.set(List.of());

        mockMvc.perform(post("/api/v1/orders/123/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer valid-token")
                        .header("Transfer-Encoding", "chunked")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.path").value("/api/v1/orders/123/cancel"));

        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingMethod.get()).isEqualTo("POST");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingBody.get()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingContentLengthHeaders.get()).doesNotContain("2");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingTransferEncodingHeaders.get()).isNullOrEmpty();
    }

    @Test
    void bodylessRefundStillDiscardsClientBody() throws Exception {
        BACKENDS.lastUserBody.set(null);
        BACKENDS.lastUserContentLengthHeaders.set(List.of());
        BACKENDS.lastUserTransferEncodingHeaders.set(List.of());

        mockMvc.perform(post("/api/v1/me/reset-pass-purchases/purchase-1/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer user-token")
                        .header("Transfer-Encoding", "chunked")
                        .content("{}"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserBody.get()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserContentLengthHeaders.get()).doesNotContain("2");
        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastUserTransferEncodingHeaders.get()).isNullOrEmpty();
    }

    @Test
    void proxiesDownstreamCancelConflictEnvelope() throws Exception {
        String expected = "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"ORDER_NOT_CANCELABLE\",\"message\":\"Order is not cancelable\"}}";

        mockMvc.perform(post("/api/v1/orders/409/cancel")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expected));
    }

    @Test
    void proxiesPublicRankingBoardWithoutAuthAndMyRankWithAuth() throws Exception {
        BACKENDS.lastTradingAuthorization.set(null);

        mockMvc.perform(get("/api/v1/rankings/seasons/current/board?limit=10")
                        .header("Authorization", "Bearer leaked-public-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.path").value("/api/v1/rankings/seasons/current/board"))
                .andExpect(jsonPath("$.success.query").value("limit=10"));

        org.assertj.core.api.Assertions.assertThat(BACKENDS.lastTradingAuthorization.get()).isNull();

        mockMvc.perform(get("/api/v1/rankings/me")
                        .header("Authorization", "Bearer valid-token")
                        .header("X-Mockcoin-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.path").value("/api/v1/rankings/me"))
                .andExpect(jsonPath("$.success.observedUserId").value("777"));
    }

    @Test
    void rejectsMyRankingWithoutAuthorization() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("UNAUTHORIZED"));
    }

    private static final class TestBackends {
        private final HttpServer user;
        private final HttpServer trading;
        private final HttpServer market;
        private final AtomicReference<String> lastTradingUserId = new AtomicReference<>();
        private final AtomicReference<List<String>> lastTradingSpoofHeaders = new AtomicReference<>(List.of());
        private final AtomicReference<String> lastTradingPath = new AtomicReference<>();
        private final AtomicReference<String> lastTradingMethod = new AtomicReference<>();
        private final AtomicReference<String> lastTradingBody = new AtomicReference<>();
        private final AtomicReference<String> lastTradingAuthorization = new AtomicReference<>();
        private final AtomicReference<String> lastTradingClientOrderId = new AtomicReference<>();
        private final AtomicReference<List<String>> lastTradingContentLengthHeaders = new AtomicReference<>(List.of());
        private final AtomicReference<List<String>> lastTradingTransferEncodingHeaders = new AtomicReference<>(List.of());
        private final AtomicReference<String> lastUserPath = new AtomicReference<>();
        private final AtomicReference<String> lastUserMethod = new AtomicReference<>();
        private final AtomicReference<String> lastUserBody = new AtomicReference<>();
        private final AtomicReference<String> lastUserAuthorization = new AtomicReference<>();
        private final AtomicReference<List<String>> lastUserForwardedFor = new AtomicReference<>(List.of());
        private final AtomicReference<List<String>> lastUserContentLengthHeaders = new AtomicReference<>(List.of());
        private final AtomicReference<List<String>> lastUserTransferEncodingHeaders = new AtomicReference<>(List.of());
        private final AtomicReference<String> lastMarketAdminToken = new AtomicReference<>();
        private final AtomicReference<String> lastMarketInternalEventToken = new AtomicReference<>();
        private final AtomicReference<String> lastMarketIngestorToken = new AtomicReference<>();
        private final AtomicReference<String> lastMarketToken = new AtomicReference<>();
        private final AtomicReference<String> lastMarketAuthorization = new AtomicReference<>();
        private final AtomicReference<String> lastMarketTraceparent = new AtomicReference<>();
        private final AtomicReference<String> lastMarketUnknownHeader = new AtomicReference<>();

        private TestBackends(HttpServer user, HttpServer trading, HttpServer market) {
            this.user = user;
            this.trading = trading;
            this.market = market;
        }

        static TestBackends start() {
            try {
                HttpServer user = HttpServer.create(new InetSocketAddress(0), 0);
                HttpServer trading = HttpServer.create(new InetSocketAddress(0), 0);
                HttpServer market = HttpServer.create(new InetSocketAddress(0), 0);
                TestBackends backends = new TestBackends(user, trading, market);
                user.createContext("/api/v1/auth/me", backends::handleAuthMe);
                user.createContext("/api/v1/auth/", backends::handleAuthRoute);
                user.createContext("/api/v1/me/profile", backends::handleUserProfileRoute);
                user.createContext("/api/v1/me/notification-agreements", backends::handleUserRoute);
                user.createContext("/api/v1/billing", backends::handleUserRoute);
                user.createContext("/api/v1/me/reset-passes", backends::handleUserRoute);
                user.createContext("/api/v1/me/reset-pass-purchases", backends::handleUserRoute);
                user.createContext("/api/v1/me/reset", backends::handleUserRoute);
                trading.createContext("/api/v1/portfolio", backends::handleTradingPortfolio);
                trading.createContext("/api/v1/orders", backends::handleTradingRoute);
                trading.createContext("/api/v1/rankings", backends::handleTradingRoute);
                market.createContext("/api/v1/markets", backends::handleMarketList);
                user.setExecutor(Executors.newSingleThreadExecutor());
                trading.setExecutor(Executors.newSingleThreadExecutor());
                market.setExecutor(Executors.newSingleThreadExecutor());
                user.start();
                trading.start();
                market.start();
                return backends;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to start test backends", exception);
            }
        }

        URI userUri() {
            return URI.create("http://127.0.0.1:" + user.getAddress().getPort());
        }

        URI tradingUri() {
            return URI.create("http://127.0.0.1:" + trading.getAddress().getPort());
        }

        URI marketUri() {
            return URI.create("http://127.0.0.1:" + market.getAddress().getPort());
        }

        void stop() {
            user.stop(0);
            trading.stop(0);
            market.stop(0);
        }

        private void handleTradingPortfolio(HttpExchange exchange) throws IOException {
            String observedUserId = exchange.getRequestHeaders().getFirst("X-Mockcoin-User-Id");
            lastTradingUserId.set(observedUserId);
            lastTradingSpoofHeaders.set(exchange.getRequestHeaders().get("X-Mockcoin-User-Id"));
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"observedUserId\":\"" + observedUserId + "\"},\"error\":null}");
        }

        private void handleUserRoute(HttpExchange exchange) throws IOException {
            lastUserPath.set(exchange.getRequestURI().getPath());
            lastUserMethod.set(exchange.getRequestMethod());
            lastUserBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastUserAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastUserContentLengthHeaders.set(exchange.getRequestHeaders().get("Content-Length"));
            lastUserTransferEncodingHeaders.set(exchange.getRequestHeaders().get("Transfer-Encoding"));
            if ("/api/v1/billing/chunked".equals(exchange.getRequestURI().getPath())) {
                writeChunkedJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"chunked\":true},\"error\":null}");
                return;
            }
            if ("/api/v1/billing/custom-header".equals(exchange.getRequestURI().getPath())) {
                exchange.getResponseHeaders().set("X-Downstream-Trace", "trace-123");
                writeJson(exchange, 202, "{\"resultType\":\"SUCCESS\",\"success\":{\"streamed\":true},\"error\":null}");
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"path\":\"" + exchange.getRequestURI().getPath() + "\",\"query\":\"" + query + "\"},\"error\":null}");
        }

        private void handleUserProfileRoute(HttpExchange exchange) throws IOException {
            lastUserPath.set(exchange.getRequestURI().getPath());
            lastUserMethod.set(exchange.getRequestMethod());
            lastUserBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastUserAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastUserForwardedFor.set(exchange.getRequestHeaders().get("X-Forwarded-For"));
            if ("POST".equals(exchange.getRequestMethod()) && "/api/v1/me/profile/nickname".equals(exchange.getRequestURI().getPath())) {
                writeJson(exchange, 400, "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"NICKNAME_NOT_ALLOWED\",\"reason\":\"부적절한 단어가 포함된 닉네임은 사용할 수 없습니다.\"}}");
                return;
            }
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"nickname\":\"tester\"},\"error\":null}");
        }

        private void handleAuthMe(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"userId\":777},\"error\":null}");
        }

        private void handleAuthRoute(HttpExchange exchange) throws IOException {
            exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"loggedOut\":true},\"error\":null}");
        }

        private void handleTradingRoute(HttpExchange exchange) throws IOException {
            String observedUserId = exchange.getRequestHeaders().getFirst("X-Mockcoin-User-Id");
            lastTradingPath.set(exchange.getRequestURI().getPath());
            lastTradingMethod.set(exchange.getRequestMethod());
            lastTradingBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastTradingAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastTradingClientOrderId.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Client-Order-Id"));
            lastTradingSpoofHeaders.set(exchange.getRequestHeaders().get("X-Mockcoin-User-Id"));
            lastTradingContentLengthHeaders.set(exchange.getRequestHeaders().get("Content-Length"));
            lastTradingTransferEncodingHeaders.set(exchange.getRequestHeaders().get("Transfer-Encoding"));
            String query = exchange.getRequestURI().getQuery();
            if ("/api/v1/orders/409/cancel".equals(exchange.getRequestURI().getPath())) {
                writeJson(exchange, 409, "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"ORDER_NOT_CANCELABLE\",\"message\":\"Order is not cancelable\"}}");
                return;
            }
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"path\":\"" + exchange.getRequestURI().getPath() + "\",\"query\":\"" + query + "\",\"observedUserId\":\"" + observedUserId + "\"},\"error\":null}");
        }

        private void handleMarketList(HttpExchange exchange) throws IOException {
            lastMarketAdminToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Admin-Token"));
            lastMarketInternalEventToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Internal-Event-Token"));
            lastMarketIngestorToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Ingestor-Token"));
            lastMarketToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Market-Token"));
            lastMarketAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastMarketTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            lastMarketUnknownHeader.set(exchange.getRequestHeaders().getFirst("X-Unlisted-Header"));
            String query = exchange.getRequestURI().getQuery();
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"query\":\"" + query + "\"},\"error\":null}");
        }

        private static void writeChunkedJson(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.getResponseHeaders().set("Connection", "X-Connection-Secret");
            exchange.getResponseHeaders().set("X-Connection-Secret", "leaked");
            exchange.sendResponseHeaders(status, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }

        private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }
    }
}
