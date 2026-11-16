package com.mockcoin.gateway.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayAdminConsoleQueryTest {
    private static final String TOKEN_HEADER = "X-Mockcoin-Admin-Token";
    private static final String TOKEN = "gateway-admin-token";
    private static final String BASE = "/admin/api/v1/admin-console";
    private static final Backends BACKENDS = Backends.start();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerRoutes(DynamicPropertyRegistry registry) {
        registry.add("mockcoin.admin.enabled", () -> true);
        registry.add("mockcoin.routes.user-service", () -> BACKENDS.uri(BACKENDS.user).toString());
        registry.add("mockcoin.routes.trading-service", () -> BACKENDS.uri(BACKENDS.trading).toString());
        registry.add("mockcoin.routes.community-service", () -> BACKENDS.uri(BACKENDS.community).toString());
        registry.add("mockcoin.admin.internal-token", () -> TOKEN);
        registry.add("mockcoin.admin.user-service-token", () -> "user-admin-token");
        registry.add("mockcoin.admin.trading-service-token", () -> "trading-admin-token");
        registry.add("mockcoin.admin.community-service-token", () -> "community-admin-token");
        registry.add("mockcoin.http.read-timeout", () -> "500ms");
        registry.add("mockcoin.security.ip-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-read-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-write-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-sensitive-requests-per-minute", () -> 1000);
    }

    @AfterAll
    static void stopBackends() {
        BACKENDS.user.stop(0);
        BACKENDS.trading.stop(0);
        BACKENDS.community.stop(0);
    }

    @BeforeEach
    void reset() {
        BACKENDS.tradingFails.set(false);
        BACKENDS.lastUserQuery.set(null);
        BACKENDS.lastUserPath.set(null);
        BACKENDS.lastTradingQuery.set(null);
        BACKENDS.lastCommunityPath.set(null);
        BACKENDS.lastCommunityQuery.set(null);
        BACKENDS.lastCommunityBody.set(null);
        BACKENDS.communityCalls.set(0);
    }

    @Test
    void 사용자_검색_페이지에_해당_사용자들의_지갑만_붙이고_검색어를_그대로_전달한다() throws Exception {
        mockMvc.perform(get(BASE + "/users").header(TOKEN_HEADER, TOKEN)
                        .param("q", "a+b 한%")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.items.length()").value(2))
                .andExpect(jsonPath("$.success.items[0].userId").value(11))
                .andExpect(jsonPath("$.success.items[0].hasWallet").value(true))
                .andExpect(jsonPath("$.success.items[0].krwBalance").value(70000000))
                .andExpect(jsonPath("$.success.items[1].hasWallet").value(false))
                .andExpect(jsonPath("$.success.items[1].krwBalance").doesNotExist())
                .andExpect(jsonPath("$.success.nextCursor").value("12"))
                .andExpect(jsonPath("$.success.walletLookupFailed").value(false));

        assertThat(BACKENDS.lastUserQuery.get()).isEqualTo("q=a%2Bb+%ED%95%9C%25&limit=2");
        assertThat(BACKENDS.lastTradingQuery.get()).isEqualTo("userIds=11%2C12");
    }

    @Test
    void 지갑_조회가_실패해도_사용자_목록은_유지하고_알_수_없음으로_표시한다() throws Exception {
        BACKENDS.tradingFails.set(true);
        mockMvc.perform(get(BASE + "/users").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.items.length()").value(2))
                .andExpect(jsonPath("$.success.items[0].hasWallet").doesNotExist())
                .andExpect(jsonPath("$.success.walletLookupFailed").value(true));
    }

    @Test
    void 사용자_상세는_계정과_포트폴리오를_합치고_포트폴리오_실패는_분리해서_알린다() throws Exception {
        mockMvc.perform(get(BASE + "/users/{userId}", 11).header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.user.nickname").value("alice"))
                .andExpect(jsonPath("$.success.portfolio.positions[0].market").value("KRW-BTC"))
                .andExpect(jsonPath("$.success.portfolioUnavailable").value(false));

        BACKENDS.tradingFails.set(true);
        mockMvc.perform(get(BASE + "/users/{userId}", 11).header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.user.userId").value(11))
                .andExpect(jsonPath("$.success.portfolio").doesNotExist())
                .andExpect(jsonPath("$.success.portfolioUnavailable").value(true));

        mockMvc.perform(get(BASE + "/users/{userId}", 404).header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.reason").value("사용자를 찾을 수 없습니다."));
    }

    @Test
    void 초기화_이력에_시즌_이름을_붙이고_필터를_전달한다() throws Exception {
        mockMvc.perform(get(BASE + "/reset-histories").header(TOKEN_HEADER, TOKEN)
                        .param("userId", "11")
                        .param("cursor", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.items[0].seasonName").value("Season Five"))
                .andExpect(jsonPath("$.success.items[1].seasonName").doesNotExist())
                .andExpect(jsonPath("$.success.hasMore").value(false));
        assertThat(BACKENDS.lastUserQuery.get()).isEqualTo("userId=11&cursor=90");
    }

    @Test
    void 시즌_랭킹_페이지에_닉네임을_붙이고_cursor를_전달한다() throws Exception {
        mockMvc.perform(get(BASE + "/ranking/seasons/{seasonId}/rankings", 5).header(TOKEN_HEADER, TOKEN)
                        .param("cursor", "50")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.rankings[0].nickname").value("alice"))
                .andExpect(jsonPath("$.success.rankings[1].nickname").doesNotExist())
                .andExpect(jsonPath("$.success.totalCount").value(2));
        assertThat(BACKENDS.lastTradingQuery.get()).isEqualTo("cursor=50&limit=50");
    }

    @Test
    void 결제_주문과_초기화권_원장은_필터를_user_service로_전달한다() throws Exception {
        mockMvc.perform(get(BASE + "/payment-orders").header(TOKEN_HEADER, TOKEN)
                        .param("userId", "11")
                        .param("status", "paid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.source").value("user"));
        assertThat(BACKENDS.lastUserPath.get()).isEqualTo("/internal/user-service/admin/payment-orders");
        assertThat(BACKENDS.lastUserQuery.get()).isEqualTo("userId=11&status=paid");

        mockMvc.perform(get(BASE + "/reset-passes").header(TOKEN_HEADER, TOKEN)
                        .param("sourceType", "purchase"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastUserPath.get()).isEqualTo("/internal/user-service/admin/reset-passes");
        assertThat(BACKENDS.lastUserQuery.get()).isEqualTo("sourceType=purchase");
    }

    @Test
    void 신고_대상_목록과_처리를_community_service로_전달한다() throws Exception {
        mockMvc.perform(get(BASE + "/community/report-targets").header(TOKEN_HEADER, TOKEN)
                        .param("status", "resolved")
                        .param("limit", "20"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastCommunityPath.get()).isEqualTo("/internal/community-service/admin/report-targets");
        assertThat(BACKENDS.lastCommunityQuery.get()).isEqualTo("status=resolved&limit=20");

        mockMvc.perform(get(BASE + "/community/report-targets/{type}/{id}/reports", "comment", 8).header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastCommunityPath.get()).isEqualTo("/internal/community-service/admin/report-targets/comment/8/reports");

        mockMvc.perform(post(BASE + "/community/report-targets/{type}/{id}/process", "post", 3).header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"resolve\",\"contentAction\":\"hide\"}"))
                .andExpect(status().isOk());
        assertThat(BACKENDS.lastCommunityPath.get()).isEqualTo("/internal/community-service/admin/report-targets/post/3/process");
        assertThat(BACKENDS.lastCommunityBody.get()).isEqualTo("{\"decision\":\"resolve\",\"contentAction\":\"hide\"}");
    }

    @Test
    void 허용되지_않은_신고_대상_유형은_downstream을_호출하지_않고_거부한다() throws Exception {
        for (String type : new String[]{"video", "..", "POST"}) {
            mockMvc.perform(post(BASE + "/community/report-targets/{type}/{id}/process", type, 3).header(TOKEN_HEADER, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"dismiss\"}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(BASE + "/community/report-targets/{type}/{id}/reports", type, 3).header(TOKEN_HEADER, TOKEN))
                    .andExpect(status().isBadRequest());
        }
        assertThat(BACKENDS.communityCalls.get()).isZero();
    }

    @Test
    void trading_service가_실패해도_overview는_반환하고_degradedServices로_알린다() throws Exception {
        BACKENDS.tradingFails.set(true);
        mockMvc.perform(get(BASE + "/overview").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.degradedServices[0]").value("trading-service"))
                .andExpect(jsonPath("$.success.activeSeasonRankings.length()").value(0));
    }

    @Test
    void 새_조회_API는_관리자_인증이_필요하다() throws Exception {
        for (String path : new String[]{"/users", "/users/11", "/payment-orders", "/reset-passes", "/reset-histories",
                "/ranking/seasons/5/rankings", "/community/report-targets", "/community/report-targets/post/3/reports"}) {
            mockMvc.perform(get(BASE + path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post(BASE + "/community/report-targets/post/3/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"dismiss\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(BACKENDS.communityCalls.get()).isZero();
    }

    private static final class Backends {
        private final HttpServer user;
        private final HttpServer trading;
        private final HttpServer community;
        private final AtomicBoolean tradingFails = new AtomicBoolean(false);
        private final AtomicReference<String> lastUserQuery = new AtomicReference<>();
        private final AtomicReference<String> lastUserPath = new AtomicReference<>();
        private final AtomicReference<String> lastTradingQuery = new AtomicReference<>();
        private final AtomicReference<String> lastCommunityPath = new AtomicReference<>();
        private final AtomicReference<String> lastCommunityQuery = new AtomicReference<>();
        private final AtomicReference<String> lastCommunityBody = new AtomicReference<>();
        private final AtomicInteger communityCalls = new AtomicInteger();

        private Backends(HttpServer user, HttpServer trading, HttpServer community) {
            this.user = user;
            this.trading = trading;
            this.community = community;
        }

        static Backends start() {
            try {
                Backends backends = new Backends(
                        HttpServer.create(new InetSocketAddress(0), 0),
                        HttpServer.create(new InetSocketAddress(0), 0),
                        HttpServer.create(new InetSocketAddress(0), 0));
                backends.user.createContext("/internal/user-service", backends::handleUser);
                backends.trading.createContext("/internal/trading-service", backends::handleTrading);
                backends.community.createContext("/internal/community-service", backends::handleCommunity);
                for (HttpServer server : new HttpServer[]{backends.user, backends.trading, backends.community}) {
                    server.setExecutor(Executors.newCachedThreadPool());
                    server.start();
                }
                return backends;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to start console query backends", exception);
            }
        }

        URI uri(HttpServer server) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void handleUser(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!"user-admin-token".equals(exchange.getRequestHeaders().getFirst(TOKEN_HEADER))) {
                write(exchange, 401, error("UNAUTHORIZED", "bad token"));
                return;
            }
            if (path.equals("/internal/user-service/users/nicknames")) {
                write(exchange, 200, success("{\"nicknames\":{\"11\":\"alice\"}}"));
                return;
            }
            lastUserPath.set(path);
            lastUserQuery.set(exchange.getRequestURI().getRawQuery());
            switch (path) {
                case "/internal/user-service/admin/users" -> write(exchange, 200, success(
                        "{\"items\":[{\"userId\":11,\"nickname\":\"alice\"},{\"userId\":12,\"nickname\":\"bob\"}],\"nextCursor\":\"12\",\"hasMore\":true}"));
                case "/internal/user-service/admin/users/11" -> write(exchange, 200, success("{\"userId\":11,\"nickname\":\"alice\",\"remainingResetPasses\":2}"));
                case "/internal/user-service/admin/users/404" -> write(exchange, 404, error("NOT_FOUND", "사용자를 찾을 수 없습니다."));
                case "/internal/user-service/admin/reset-histories" -> write(exchange, 200, success(
                        "{\"items\":[{\"historyId\":2,\"userId\":11,\"seasonId\":5},{\"historyId\":1,\"userId\":11,\"seasonId\":6}],\"nextCursor\":null,\"hasMore\":false}"));
                default -> write(exchange, 200, success("{\"source\":\"user\"}"));
            }
        }

        private void handleTrading(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (tradingFails.get()) {
                write(exchange, 503, error("TRADING_DOWN", "down"));
                return;
            }
            switch (path) {
                case "/internal/trading-service/admin/wallets" -> {
                    lastTradingQuery.set(exchange.getRequestURI().getRawQuery());
                    write(exchange, 200, success("{\"wallets\":[{\"userId\":11,\"seedKrwBalance\":100000000,\"krwBalance\":70000000}]}"));
                }
                case "/internal/trading-service/admin/users/11/portfolio" -> write(exchange, 200, success(
                        "{\"userId\":11,\"hasWallet\":true,\"krwBalance\":70000000,\"positions\":[{\"market\":\"KRW-BTC\",\"quantity\":0.1}]}"));
                case "/internal/trading-service/admin/ranking/seasons" -> write(exchange, 200, success(
                        "{\"seasons\":[{\"seasonId\":5,\"name\":\"Season Five\"}]}"));
                case "/internal/trading-service/admin/ranking/seasons/5/rankings" -> {
                    lastTradingQuery.set(exchange.getRequestURI().getRawQuery());
                    write(exchange, 200, success(
                            "{\"rankings\":[{\"userId\":11,\"rankNo\":1},{\"userId\":99,\"rankNo\":2}],\"nextCursor\":null,\"hasMore\":false,\"totalCount\":2}"));
                }
                default -> write(exchange, 404, error("NOT_FOUND", "no route"));
            }
        }

        private void handleCommunity(HttpExchange exchange) throws IOException {
            communityCalls.incrementAndGet();
            lastCommunityPath.set(exchange.getRequestURI().getPath());
            lastCommunityQuery.set(exchange.getRequestURI().getRawQuery());
            lastCommunityBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            write(exchange, 200, success("{\"source\":\"community\"}"));
        }

        private static String success(String body) {
            return "{\"resultType\":\"SUCCESS\",\"success\":" + body + ",\"error\":null}";
        }

        private static String error(String code, String reason) {
            return "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"" + code + "\",\"reason\":\"" + reason + "\"}}";
        }

        private static void write(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }
    }
}
