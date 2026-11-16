package com.mockcoin.gateway.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayAdminFacadeControllerTest {
    private static final TestAdminBackends BACKENDS = TestAdminBackends.start();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerRoutes(DynamicPropertyRegistry registry) {
        registry.add("mockcoin.admin.enabled", () -> true);
        registry.add("mockcoin.routes.user-service", () -> BACKENDS.userUri().toString());
        registry.add("mockcoin.routes.trading-service", () -> BACKENDS.tradingUri().toString());
        registry.add("mockcoin.routes.market-service", () -> BACKENDS.marketUri().toString());
        registry.add("mockcoin.routes.ingestor-service", () -> BACKENDS.ingestorUri().toString());
        registry.add("mockcoin.routes.community-service", () -> BACKENDS.communityUri().toString());
        registry.add("mockcoin.admin.internal-token", () -> "gateway-admin-token");
        registry.add("mockcoin.admin.user-service-token", () -> "user-admin-token");
        registry.add("mockcoin.admin.trading-service-token", () -> "trading-admin-token");
        registry.add("mockcoin.admin.market-service-token", () -> "market-internal-token");
        registry.add("mockcoin.admin.ingestor-service-token", () -> "ingestor-internal-token");
        registry.add("mockcoin.admin.community-service-token", () -> "community-admin-token");
        registry.add("mockcoin.http.read-timeout", () -> "250ms");
        registry.add("mockcoin.websocket.ping-interval-seconds", () -> "31");
        registry.add("mockcoin.security.ip-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-auth-requests-per-window", () -> 1000);
        registry.add("mockcoin.security.admin-read-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-write-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-sensitive-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.order-requests-per-minute", () -> 77);
        registry.add("mockcoin.security.cors-allowed-origins", () -> "https://admin.example");
    }

    @AfterAll
    static void stopBackends() {
        BACKENDS.stop();
    }

    @Test
    void adminFacadeRequiresGatewayAdminToken() throws Exception {
        mockMvc.perform(get("/admin/api/v1/admin-console/billing/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void forwardsBillingProductRequestsToUserServiceWithInternalToken() throws Exception {
        BACKENDS.lastUserToken.set(null);

        mockMvc.perform(get("/admin/api/v1/admin-console/billing/products")
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.source").value("user"));

        assertThat(BACKENDS.lastUserToken.get()).isEqualTo("user-admin-token");
    }

    @Test
    void aggregatesAdminOverviewFromUserAndTradingFragments() throws Exception {
        BACKENDS.lastUserToken.set(null);
        BACKENDS.lastTradingToken.set(null);
        BACKENDS.lastMarketToken.set(null);
        BACKENDS.lastIngestorToken.set(null);

        mockMvc.perform(get("/admin/api/v1/admin-console/overview")
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.users[0].hasWallet").value(true))
                .andExpect(jsonPath("$.success.users[0].krwBalance").value(125000000.0))
                .andExpect(jsonPath("$.success.resetHistories").doesNotExist())
                .andExpect(jsonPath("$.success.resetPasses").doesNotExist())
                .andExpect(jsonPath("$.success.degradedServices.length()").value(0))
                .andExpect(jsonPath("$.success.activeSeasonRankings[0].nickname").value("overview-user"))
                .andExpect(jsonPath("$.success.billingProductTypes[0]").value("RESET_PASS"))
                .andExpect(jsonPath("$.success.seasonStatuses[0]").value("SCHEDULED"))
                .andExpect(jsonPath("$.success.runtime.websocket.pingIntervalSeconds").value(31))
                .andExpect(jsonPath("$.success.runtime.security.rateLimitEnabled").value(true))
                .andExpect(jsonPath("$.success.runtime.security.ipRequestsPerMinute").value(1000))
                .andExpect(jsonPath("$.success.runtime.security.orderRequestsPerMinute").value(77))
                .andExpect(jsonPath("$.success.runtime.security.corsAllowedOrigins[0]").value("https://admin.example"))
                .andExpect(jsonPath("$.success.runtime.integrations.candleCascadeAggregationEnabled").value(true))
                .andExpect(jsonPath("$.success.runtime.integrations.ingestorEnabled").value(true))
                .andExpect(jsonPath("$.success.runtime.integrations.upbitRestBaseUrl").value("https://quotation.example"))
                .andExpect(jsonPath("$.success.runtime.integrations.gatewayRedisMarketEventsEnabled").value(false))
                .andExpect(jsonPath("$.success.runtime.integrations.gatewayRedisPrivateEventsEnabled").value(false));

        assertThat(BACKENDS.lastUserToken.get()).isEqualTo("user-admin-token");
        assertThat(BACKENDS.lastTradingToken.get()).isEqualTo("trading-admin-token");
        assertThat(BACKENDS.lastMarketToken.get()).isEqualTo("market-internal-token");
        assertThat(BACKENDS.lastIngestorToken.get()).isEqualTo("ingestor-internal-token");
    }

    @Test
    void skipsSlowOptionalRuntimeFragmentsWithoutFailingOverview() throws Exception {
        BACKENDS.slowIngestorOverview.set(true);
        try {
            mockMvc.perform(get("/admin/api/v1/admin-console/overview")
                            .header("X-Mockcoin-Admin-Token", "gateway-admin-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success.users[0].hasWallet").value(true))
                    .andExpect(jsonPath("$.success.activeSeasonRankings[0].nickname").value("overview-user"))
                    .andExpect(jsonPath("$.success.runtime.integrations.ingestorEnabled").doesNotExist());
        } finally {
            BACKENDS.slowIngestorOverview.set(false);
        }
    }

    @Test
    void forwardsWalletAndRankingRequestsToTradingServiceWithInternalToken() throws Exception {
        BACKENDS.lastTradingToken.set(null);

        mockMvc.perform(post("/admin/api/v1/admin-console/users/{userId}/wallet/ensure", 77)
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.source").value("trading"));

        assertThat(BACKENDS.lastTradingToken.get()).isEqualTo("trading-admin-token");
    }

    @Test
    void forwardsUserOrderQueriesToTradingService() throws Exception {
        BACKENDS.lastTradingPath.set(null);
        BACKENDS.lastTradingQuery.set(null);
        BACKENDS.lastTradingMethod.set(null);

        mockMvc.perform(get("/admin/api/v1/admin-console/users/{userId}/orders", 303)
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                        .param("state", "wait")
                        .param("cursor", "0")
                        .param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.source").value("trading"));

        assertThat(BACKENDS.lastTradingPath.get()).isEqualTo("/internal/trading-service/admin/users/303/orders");
        assertThat(BACKENDS.lastTradingQuery.get()).isEqualTo("state=wait&cursor=0&limit=25");
        assertThat(BACKENDS.lastTradingMethod.get()).isEqualTo("GET");
        assertThat(BACKENDS.lastTradingToken.get()).isEqualTo("trading-admin-token");
    }

    @Test
    void forwardsUserAdminMutationRouteMatrixWithBodies() throws Exception {
        BACKENDS.lastUserPath.set(null);
        BACKENDS.lastUserMethod.set(null);
        BACKENDS.lastUserBody.set(null);

        mockMvc.perform(put("/admin/api/v1/admin-console/billing/products/{productId}", 12)
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.source").value("user"));
        assertThat(BACKENDS.lastUserPath.get()).isEqualTo("/internal/user-service/admin/billing/products/12");
        assertThat(BACKENDS.lastUserMethod.get()).isEqualTo("PUT");
        assertThat(BACKENDS.lastUserBody.get()).isEqualTo("{\"active\":false}");
        assertThat(BACKENDS.lastUserToken.get()).isEqualTo("user-admin-token");

        mockMvc.perform(post("/admin/api/v1/admin-console/users/{userId}/reset-passes", 88)
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastUserPath.get()).isEqualTo("/internal/user-service/admin/users/88/reset-passes");
        assertThat(BACKENDS.lastUserMethod.get()).isEqualTo("POST");
        assertThat(BACKENDS.lastUserBody.get()).isEqualTo("{\"quantity\":2}");

        mockMvc.perform(post("/admin/api/v1/admin-console/reset-passes/{passId}/revoke", 99)
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastUserPath.get()).isEqualTo("/internal/user-service/admin/reset-passes/99/revoke");
        assertThat(BACKENDS.lastUserBody.get()).isEqualTo("{\"quantity\":1}");
    }

    @Test
    void forwardsTradingAdminRankingRouteMatrixWithBodies() throws Exception {
        BACKENDS.lastTradingPath.set(null);
        BACKENDS.lastTradingMethod.set(null);
        BACKENDS.lastTradingBody.set(null);

        mockMvc.perform(get("/admin/api/v1/admin-console/ranking/seasons")
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastTradingPath.get()).isEqualTo("/internal/trading-service/admin/ranking/seasons");
        assertThat(BACKENDS.lastTradingMethod.get()).isEqualTo("GET");
        assertThat(BACKENDS.lastTradingToken.get()).isEqualTo("trading-admin-token");

        mockMvc.perform(post("/admin/api/v1/admin-console/ranking/seasons")
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Season\"}"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastTradingPath.get()).isEqualTo("/internal/trading-service/admin/ranking/seasons");
        assertThat(BACKENDS.lastTradingMethod.get()).isEqualTo("POST");
        assertThat(BACKENDS.lastTradingBody.get()).isEqualTo("{\"name\":\"Season\"}");

        mockMvc.perform(put("/admin/api/v1/admin-console/ranking/seasons/{seasonId}", 44)
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ended\"}"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastTradingPath.get()).isEqualTo("/internal/trading-service/admin/ranking/seasons/44");
        assertThat(BACKENDS.lastTradingMethod.get()).isEqualTo("PUT");
        assertThat(BACKENDS.lastTradingBody.get()).isEqualTo("{\"status\":\"ended\"}");

        mockMvc.perform(post("/admin/api/v1/admin-console/ranking/seasons/{seasonId}/recalculate", 44)
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastTradingPath.get()).isEqualTo("/internal/trading-service/admin/ranking/seasons/44/recalculate");
        assertThat(BACKENDS.lastTradingMethod.get()).isEqualTo("POST");
    }

    @Test
    void forwardsCommunityAdminModerationRoutes() throws Exception {
        BACKENDS.lastCommunityToken.set(null);
        BACKENDS.lastCommunityPath.set(null);
        BACKENDS.lastCommunityMethod.set(null);
        BACKENDS.lastCommunityQuery.set(null);

        mockMvc.perform(get("/admin/api/v1/admin-console/community/reports")
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                        .header(HttpHeaders.ACCEPT, MediaType.ALL_VALUE)
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success.source").value("community"));
        assertThat(BACKENDS.lastCommunityPath.get()).isEqualTo("/internal/community-service/admin/reports");
        assertThat(BACKENDS.lastCommunityQuery.get()).isEqualTo("limit=50");
        assertThat(BACKENDS.lastCommunityMethod.get()).isEqualTo("GET");
        assertThat(BACKENDS.lastCommunityToken.get()).isEqualTo("community-admin-token");

        mockMvc.perform(get("/admin/api/v1/admin-console/community/published-posts")
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.source").value("community"));
        assertThat(BACKENDS.lastCommunityPath.get()).isEqualTo("/internal/community-service/admin/published-posts");
        assertThat(BACKENDS.lastCommunityQuery.get()).isEqualTo("limit=50");
        assertThat(BACKENDS.lastCommunityMethod.get()).isEqualTo("GET");
        assertThat(BACKENDS.lastCommunityToken.get()).isEqualTo("community-admin-token");

        mockMvc.perform(get("/admin/api/v1/admin-console/community/comments/{commentId}", 9)
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastCommunityPath.get()).isEqualTo("/internal/community-service/admin/comments/9");

        mockMvc.perform(post("/admin/api/v1/admin-console/community/posts/{postId}/hide", 12)
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastCommunityPath.get()).isEqualTo("/internal/community-service/admin/posts/12/hide");
        assertThat(BACKENDS.lastCommunityMethod.get()).isEqualTo("POST");

        mockMvc.perform(post("/admin/api/v1/admin-console/community/comments/{commentId}/hide", 9)
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastCommunityPath.get()).isEqualTo("/internal/community-service/admin/comments/9/hide");
    }

    @Test
    void communityReportsHtmlErrorIsConvertedToJson() throws Exception {
        expectCommunityReportsHtmlConverted(404, 404);
        expectCommunityReportsHtmlConverted(200, 502);
    }

    @Test
    void communityJsonWithHtmlCompatibleContentTypeStillSucceeds() throws Exception {
        BACKENDS.communityJsonContentType.set("text/html;charset=UTF-8");
        try {
            mockMvc.perform(get("/admin/api/v1/admin-console/community/published-posts")
                            .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success.source").value("community"));
        } finally {
            BACKENDS.communityJsonContentType.set(null);
        }
    }

    @Test
    void communityEmptyErrorIsConvertedToJson() throws Exception {
        BACKENDS.communityEmptyErrorStatus.set(502);
        try {
            mockMvc.perform(get("/admin/api/v1/admin-console/community/hidden-posts")
                            .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                            .param("limit", "50"))
                    .andExpect(status().isBadGateway())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error.errorCode").value("DOWNSTREAM_ADMIN_FAILED"))
                    .andExpect(jsonPath("$.error.reason").value("관리 API가 비어 있는 실패 응답을 반환했습니다."));
        } finally {
            BACKENDS.communityEmptyErrorStatus.set(0);
        }
    }

    private void expectCommunityReportsHtmlConverted(int downstreamStatus, int expectedStatus) throws Exception {
        BACKENDS.communityReportsHtmlStatus.set(downstreamStatus);
        try {
            mockMvc.perform(get("/admin/api/v1/admin-console/community/reports")
                            .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                            .header(HttpHeaders.ACCEPT, MediaType.ALL_VALUE)
                            .param("limit", "50"))
                    .andExpect(status().is(expectedStatus))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.error.errorCode").value("DOWNSTREAM_ADMIN_FAILED"))
                    .andExpect(jsonPath("$.error.reason").value("관리 API가 JSON이 아닌 응답을 반환했습니다."));
        } finally {
            BACKENDS.communityReportsHtmlStatus.set(0);
        }
    }

    @Test
    void preservesDownstreamErrorEnvelope() throws Exception {
        mockMvc.perform(post("/admin/api/v1/admin-console/billing/products")
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerSku\":\"duplicate\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.errorCode").value("DUPLICATE_PRODUCT"));
    }

    private static final class TestAdminBackends {
        private final HttpServer user;
        private final HttpServer trading;
        private final HttpServer market;
        private final HttpServer ingestor;
        private final HttpServer community;
        private final AtomicReference<String> lastUserToken = new AtomicReference<>();
        private final AtomicReference<String> lastTradingToken = new AtomicReference<>();
        private final AtomicReference<String> lastMarketToken = new AtomicReference<>();
        private final AtomicReference<String> lastIngestorToken = new AtomicReference<>();
        private final AtomicReference<String> lastCommunityToken = new AtomicReference<>();
        private final AtomicReference<String> lastCommunityPath = new AtomicReference<>();
        private final AtomicReference<String> lastCommunityQuery = new AtomicReference<>();
        private final AtomicReference<String> lastCommunityMethod = new AtomicReference<>();
        private final AtomicReference<String> lastUserPath = new AtomicReference<>();
        private final AtomicReference<String> lastUserMethod = new AtomicReference<>();
        private final AtomicReference<String> lastUserBody = new AtomicReference<>();
        private final AtomicReference<String> lastTradingPath = new AtomicReference<>();
        private final AtomicReference<String> lastTradingQuery = new AtomicReference<>();
        private final AtomicReference<String> lastTradingMethod = new AtomicReference<>();
        private final AtomicReference<String> lastTradingBody = new AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean slowIngestorOverview = new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicInteger communityReportsHtmlStatus = new java.util.concurrent.atomic.AtomicInteger(0);
        private final java.util.concurrent.atomic.AtomicInteger communityEmptyErrorStatus = new java.util.concurrent.atomic.AtomicInteger(0);
        private final AtomicReference<String> communityJsonContentType = new AtomicReference<>();

        private TestAdminBackends(HttpServer user, HttpServer trading, HttpServer market, HttpServer ingestor, HttpServer community) {
            this.user = user;
            this.trading = trading;
            this.market = market;
            this.ingestor = ingestor;
            this.community = community;
        }

        static TestAdminBackends start() {
            try {
                HttpServer user = HttpServer.create(new InetSocketAddress(0), 0);
                HttpServer trading = HttpServer.create(new InetSocketAddress(0), 0);
                HttpServer market = HttpServer.create(new InetSocketAddress(0), 0);
                HttpServer ingestor = HttpServer.create(new InetSocketAddress(0), 0);
                HttpServer community = HttpServer.create(new InetSocketAddress(0), 0);
                TestAdminBackends backends = new TestAdminBackends(user, trading, market, ingestor, community);
                user.createContext("/internal/user-service/admin/billing/products", backends::handleUserBillingProducts);
                user.createContext("/internal/user-service/admin/users", backends::handleUserAdminRoute);
                user.createContext("/internal/user-service/admin/reset-passes", backends::handleUserAdminRoute);
                user.createContext("/internal/user-service/admin/overview", backends::handleUserOverview);
                user.createContext("/internal/user-service/users/nicknames", backends::handleUserNicknames);
                trading.createContext("/internal/trading-service/admin/users/77/wallet/ensure", backends::handleTradingWalletEnsure);
                trading.createContext("/internal/trading-service/admin/users", backends::handleTradingAdminRoute);
                trading.createContext("/internal/trading-service/admin/ranking/seasons", backends::handleTradingAdminRoute);
                trading.createContext("/internal/trading-service/admin/overview", backends::handleTradingOverview);
                trading.createContext("/internal/trading-service/admin/wallets", backends::handleTradingWallets);
                market.createContext("/internal/market-service/admin/overview", backends::handleMarketOverview);
                ingestor.createContext("/internal/ingestor-service/admin/overview", backends::handleIngestorOverview);
                community.createContext("/internal/community-service/admin", backends::handleCommunityAdminRoute);
                user.setExecutor(Executors.newCachedThreadPool());
                trading.setExecutor(Executors.newCachedThreadPool());
                market.setExecutor(Executors.newCachedThreadPool());
                ingestor.setExecutor(Executors.newCachedThreadPool());
                community.setExecutor(Executors.newCachedThreadPool());
                user.start();
                trading.start();
                market.start();
                ingestor.start();
                community.start();
                return backends;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to start admin test backends", exception);
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

        URI ingestorUri() {
            return URI.create("http://127.0.0.1:" + ingestor.getAddress().getPort());
        }

        URI communityUri() {
            return URI.create("http://127.0.0.1:" + community.getAddress().getPort());
        }

        void stop() {
            user.stop(0);
            trading.stop(0);
            market.stop(0);
            ingestor.stop(0);
            community.stop(0);
        }

        private void handleUserBillingProducts(HttpExchange exchange) throws IOException {
            lastUserToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Admin-Token"));
            lastUserPath.set(exchange.getRequestURI().getPath());
            lastUserMethod.set(exchange.getRequestMethod());
            lastUserBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if ("POST".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 409, "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"DUPLICATE_PRODUCT\",\"reason\":\"duplicate\",\"data\":null}}");
                return;
            }
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"source\":\"user\"},\"error\":null}");
        }

        private void handleUserAdminRoute(HttpExchange exchange) throws IOException {
            lastUserToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Admin-Token"));
            lastUserPath.set(exchange.getRequestURI().getPath());
            lastUserMethod.set(exchange.getRequestMethod());
            lastUserBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"source\":\"user\"},\"error\":null}");
        }

        private void handleUserOverview(HttpExchange exchange) throws IOException {
            lastUserToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Admin-Token"));
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"runtime\":{\"auth\":{\"tossMockEnabled\":true},\"billing\":{\"iapMockEnabled\":true},\"integrations\":{\"tossMtlsEnabled\":false}},\"counts\":{\"users\":1},\"billingProducts\":[{\"id\":1,\"providerSku\":\"reset\",\"productType\":\"RESET_PASS\",\"grantAmount\":1,\"active\":true}],\"users\":[{\"userId\":303,\"tossUserKey\":\"toss-303\",\"role\":\"USER\",\"nickname\":\"overview-user\",\"profileImageUrl\":null,\"remainingResetPasses\":1,\"resetCount\":1,\"activeSessionCount\":1}],\"paymentOrders\":[],\"billingProductTypes\":[\"RESET_PASS\"]},\"error\":null}");
        }

        private void handleUserNicknames(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"nicknames\":{\"303\":\"overview-user\"}},\"error\":null}");
        }

        private void handleTradingWallets(HttpExchange exchange) throws IOException {
            lastTradingQuery.set(exchange.getRequestURI().getRawQuery());
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"wallets\":[{\"userId\":303,\"seedKrwBalance\":100000000,\"krwBalance\":125000000}]},\"error\":null}");
        }

        private void handleTradingWalletEnsure(HttpExchange exchange) throws IOException {
            lastTradingToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Admin-Token"));
            lastTradingPath.set(exchange.getRequestURI().getPath());
            lastTradingMethod.set(exchange.getRequestMethod());
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"source\":\"trading\"},\"error\":null}");
        }

        private void handleTradingAdminRoute(HttpExchange exchange) throws IOException {
            lastTradingToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Admin-Token"));
            lastTradingPath.set(exchange.getRequestURI().getPath());
            lastTradingQuery.set(exchange.getRequestURI().getRawQuery());
            lastTradingMethod.set(exchange.getRequestMethod());
            lastTradingBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"source\":\"trading\"},\"error\":null}");
        }

        private void handleTradingOverview(HttpExchange exchange) throws IOException {
            lastTradingToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Admin-Token"));
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"runtime\":{\"order\":{\"maxPriceDeviation\":0.5}},\"counts\":{\"wallets\":1},\"rankingSeasons\":[{\"seasonId\":88,\"name\":\"Overview Season\",\"status\":\"active\",\"active\":true}],\"activeSeasonId\":88,\"activeSeasonRankings\":[{\"seasonId\":88,\"userId\":303,\"rank\":1,\"totalReturnRate\":0.25,\"pnlAmount\":25000000,\"eligible\":true}],\"seasonStatuses\":[\"SCHEDULED\",\"ACTIVE\",\"ENDED\"]},\"error\":null}");
        }

        private void handleMarketOverview(HttpExchange exchange) throws IOException {
            lastMarketToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Market-Token"));
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"runtime\":{\"integrations\":{\"candleCascadeAggregationEnabled\":true,\"candleOnDemandBackfillEnabled\":true,\"ingestorServiceBaseUrl\":\"http://ingestor.internal\"}}},\"error\":null}");
        }

        private void handleCommunityAdminRoute(HttpExchange exchange) throws IOException {
            lastCommunityToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Community-Token"));
            lastCommunityPath.set(exchange.getRequestURI().getPath());
            lastCommunityQuery.set(exchange.getRequestURI().getRawQuery());
            lastCommunityMethod.set(exchange.getRequestMethod());
            exchange.getRequestBody().readAllBytes();
            int htmlStatus = communityReportsHtmlStatus.get();
            if (htmlStatus > 0 && exchange.getRequestURI().getPath().endsWith("/reports")) {
                writeHtml(exchange, htmlStatus, "<!DOCTYPE html><html><body>not found</body></html>");
                return;
            }
            int emptyErrorStatus = communityEmptyErrorStatus.get();
            if (emptyErrorStatus > 0) {
                exchange.sendResponseHeaders(emptyErrorStatus, 0);
                exchange.close();
                return;
            }
            String jsonBody = "{\"resultType\":\"SUCCESS\",\"success\":{\"source\":\"community\",\"runtime\":{\"integrations\":{\"communityInternalTokenConfigured\":true}},\"counts\":{\"publishedPosts\":1,\"hiddenPosts\":0,\"reports\":0}},\"error\":null}";
            String contentType = communityJsonContentType.get();
            if (contentType != null && !contentType.isBlank()) {
                writeBody(exchange, 200, jsonBody, contentType);
                return;
            }
            writeJson(exchange, 200, jsonBody);
        }

        private void handleIngestorOverview(HttpExchange exchange) throws IOException {
            lastIngestorToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Ingestor-Token"));
            if (slowIngestorOverview.get()) {
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"runtime\":{\"integrations\":{\"ingestorEnabled\":true,\"upbitRestBaseUrl\":\"https://quotation.example\",\"upbitWebsocketUrl\":\"wss://ws.example\"}}},\"error\":null}");
        }

        private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
            writeBody(exchange, status, body, "application/json;charset=UTF-8");
        }

        private static void writeHtml(HttpExchange exchange, int status, String body) throws IOException {
            writeBody(exchange, status, body, "text/html;charset=UTF-8");
        }

        private static void writeBody(HttpExchange exchange, int status, String body, String contentType) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }
    }
}
