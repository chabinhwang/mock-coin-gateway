package com.mockcoin.gateway.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

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
class GatewayApiSecurityTest {
    private static final TestBackends BACKENDS = TestBackends.start();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerRoutes(DynamicPropertyRegistry registry) {
        registry.add("mockcoin.routes.user-service", () -> BACKENDS.userUri().toString());
        registry.add("mockcoin.routes.trading-service", () -> BACKENDS.tradingUri().toString());
        registry.add("mockcoin.routes.market-service", () -> BACKENDS.marketUri().toString());
        registry.add("mockcoin.security.ip-requests-per-minute", () -> 100);
        registry.add("mockcoin.security.order-requests-per-minute", () -> 100);
        registry.add("mockcoin.security.auth-requests-per-window", () -> 1);
        registry.add("mockcoin.security.auth-window-seconds", () -> 60);
        registry.add("mockcoin.security.cors-allowed-origins", () -> "https://app.example.com");
    }

    @AfterAll
    static void stopBackends() {
        BACKENDS.stop();
    }

    @Test
    void appliesSecurityHeadersToApiResponses() throws Exception {
        mockMvc.perform(get("/api/v1/markets"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")));
    }

    @Test
    void allowsConfiguredCorsPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/markets")
                        .header(HttpHeaders.ORIGIN, "https://app.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.example.com"));
    }

    @Test
    void corsPreflightExposesAcceptAndTraceHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/markets")
                        .header(HttpHeaders.ORIGIN, "https://app.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Accept, traceparent, tracestate"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsString("Accept")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsString("traceparent")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsString("tracestate")));
    }

    @Test
    void rateLimitsRepeatedAuthRequests() throws Exception {
        mockMvc.perform(post("/api/v1/auth/toss/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"a\",\"referrer\":\"b\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/toss/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"a\",\"referrer\":\"b\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.resultType").value("FAIL"))
                .andExpect(jsonPath("$.error.errorCode").value("RATE_LIMIT_EXCEEDED"));
    }

    private static final class TestBackends {
        private final HttpServer user;
        private final HttpServer trading;
        private final HttpServer market;

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
                user.createContext("/api/v1/auth/toss/exchange", exchange -> writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"ok\":true},\"error\":null}"));
                market.createContext("/api/v1/markets", exchange -> writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"markets\":[]},\"error\":null}"));
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
