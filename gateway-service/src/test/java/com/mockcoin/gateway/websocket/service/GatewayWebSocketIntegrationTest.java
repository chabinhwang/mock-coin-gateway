package com.mockcoin.gateway.websocket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class GatewayWebSocketIntegrationTest {
    private static final TestUserBackend USER_BACKEND = TestUserBackend.start();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerRoutes(DynamicPropertyRegistry registry) {
        registry.add("mockcoin.routes.user-service", () -> USER_BACKEND.uri().toString());
        registry.add("mockcoin.routes.trading-service", () -> "http://127.0.0.1:1");
        registry.add("mockcoin.routes.market-service", () -> "http://127.0.0.1:1");
        registry.add("mockcoin.websocket.auth-timeout-seconds", () -> "5");
        registry.add("mockcoin.websocket.internal-event-token", () -> "test-internal-token");
        // 운영처럼 API 호스트 origin은 WebSocket 허용 목록에만 두고 REST CORS 허용 목록(기본값)에는 넣지 않는다.
        registry.add("mockcoin.websocket.allowed-origin-patterns", () -> "https://*.apps.tossmini.com,https://api.example.com");
    }

    @AfterAll
    static void stopBackend() {
        USER_BACKEND.stop();
    }

    @Test
    void publicTickerSubscribersReceiveInternalMarketEvents() throws Exception {
        TestSocket socket = connect("/ws");
        socket.send("{\"action\":\"subscribe\",\"channels\":[\"ticker\"],\"market\":\"krw-btc\"}");

        JsonNode ack = socket.nextJson(objectMapper);
        assertThat(ack.path("type").asText()).isEqualTo("subscribe.ok");
        assertThat(ack.path("market").asText()).isEqualTo("KRW-BTC");

        ResponseEntity<String> response = postInternal("/internal/gateway/events/public", Map.of(
                "type", "ticker.update",
                "market", "KRW-BTC",
                "data", Map.of("tradePrice", 135200000, "changeRate", 0.0234)
        ));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"delivered\":1");

        JsonNode event = socket.nextJson(objectMapper);
        assertThat(event.path("type").asText()).isEqualTo("ticker.update");
        assertThat(event.path("market").asText()).isEqualTo("KRW-BTC");
        assertThat(event.path("data").path("tradePrice").asInt()).isEqualTo(135200000);
    }

    @Test
    void publicCandleSubscribersReceiveReconcileEventsOnly() throws Exception {
        TestSocket socket = connect("/ws");
        socket.send("{\"action\":\"subscribe\",\"channels\":[\"candle\"],\"market\":\"krw-btc\"}");

        JsonNode ack = socket.nextJson(objectMapper);
        assertThat(ack.path("type").asText()).isEqualTo("subscribe.ok");
        assertThat(ack.path("market").asText()).isEqualTo("KRW-BTC");

        ResponseEntity<String> rawUpdateResponse = postInternal("/internal/gateway/events/public", Map.of(
                "type", "candle.update",
                "market", "KRW-BTC",
                "data", Map.of("interval", "1m", "close", 135200000)
        ));
        assertThat(rawUpdateResponse.getStatusCode().is4xxClientError()).isTrue();

        ResponseEntity<String> reconcileResponse = postInternal("/internal/gateway/events/public", Map.of(
                "type", "candle.reconcile",
                "market", "KRW-BTC",
                "data", Map.of(
                        "interval", "1m",
                        "candles", List.of(Map.of("time", 1777075200, "close", 135300000))
                )
        ));

        assertThat(reconcileResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(reconcileResponse.getBody()).contains("\"delivered\":1");

        JsonNode reconcileEvent = socket.nextJson(objectMapper);
        assertThat(reconcileEvent.path("type").asText()).isEqualTo("candle.reconcile");
        assertThat(reconcileEvent.path("market").asText()).isEqualTo("KRW-BTC");
        assertThat(reconcileEvent.path("data").path("interval").asText()).isEqualTo("1m");
        assertThat(reconcileEvent.path("data").path("candles").get(0).path("close").asInt()).isEqualTo(135300000);
    }

    @Test
    void tokenAuthenticatedPrivateSubscribersReceiveOnlyTheirEvents() throws Exception {
        TestSocket socket = connect("/ws?token=valid-token");
        JsonNode auth = socket.nextJson(objectMapper);
        assertThat(auth.path("type").asText()).isEqualTo("auth.ok");
        assertThat(auth.path("userId").asLong()).isEqualTo(777L);

        socket.send("{\"action\":\"subscribe\",\"channels\":[\"order\"]}");
        JsonNode ack = socket.nextJson(objectMapper);
        assertThat(ack.path("type").asText()).isEqualTo("subscribe.ok");

        ResponseEntity<String> wrongUser = postInternal("/internal/gateway/events/private/778", Map.of(
                "type", "order.update",
                "data", Map.of("orderId", 999)
        ));
        assertThat(wrongUser.getBody()).contains("\"delivered\":0");

        ResponseEntity<String> targetUser = postInternal("/internal/gateway/events/private/777", Map.of(
                "type", "order.update",
                "data", Map.of("orderId", 123)
        ));
        assertThat(targetUser.getBody()).contains("\"delivered\":1");

        JsonNode event = socket.nextJson(objectMapper);
        assertThat(event.path("type").asText()).isEqualTo("order.update");
        assertThat(event.path("data").path("orderId").asInt()).isEqualTo(123);
    }

    @Test
    void internalWebsocketPublicEventsAreAcceptedAndBroadcasted() throws Exception {
        TestSocket publicSocket = connect("/ws");
        publicSocket.send("{\"action\":\"subscribe\",\"channels\":[\"ticker\"],\"market\":\"krw-btc\"}");
        JsonNode ack = publicSocket.nextJson(objectMapper);
        assertThat(ack.path("type").asText()).isEqualTo("subscribe.ok");

        TestSocket internalSocket = connectInternal("/internal/gateway/events/public/ws", "test-internal-token");
        internalSocket.send("""
                {
                  "type":"ticker.update",
                  "market":"KRW-BTC",
                  "ts":"2025-03-10T01:00:00Z",
                  "data":{"tradePrice":135200000,"changeRate":0.0234}
                }
                """);

        JsonNode event = publicSocket.nextJson(objectMapper);
        assertThat(event.path("type").asText()).isEqualTo("ticker.update");
        assertThat(event.path("market").asText()).isEqualTo("KRW-BTC");
        assertThat(event.path("data").path("tradePrice").asInt()).isEqualTo(135200000);
    }

    @Test
    void apiHostOriginAllowedOnlyForWebSocketReceivesMarketListEvents() throws Exception {
        // RN 미니앱 WebSocket은 Origin을 https://<API 호스트>로 보낸다. 이 origin이 REST CORS로 다시 검사되면
        // WebSocket 허용 목록에 있어도 핸드셰이크가 403(Invalid CORS request)으로 막힌다.
        TestSocket socket = connectWithOrigin("/ws", "https://api.example.com");
        socket.send("{\"action\":\"subscribe\",\"channels\":[\"marketList\"],\"market\":null}");
        JsonNode ack = socket.nextJson(objectMapper);
        assertThat(ack.path("type").asText()).isEqualTo("subscribe.ok");

        ResponseEntity<String> response = postInternal("/internal/gateway/events/public", Map.of(
                "type", "marketList.update",
                "data", Map.of("markets", List.of(Map.of("market", "KRW-BTC", "currentPrice", 135200000)))
        ));
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode event = socket.nextJson(objectMapper);
        assertThat(event.path("type").asText()).isEqualTo("marketList.update");
        assertThat(event.path("data").path("markets").get(0).path("currentPrice").asInt()).isEqualTo(135200000);
    }

    @Test
    void handshakeRejectsOriginOutsideWebSocketAllowList() {
        Throwable failure = catchThrowable(() -> connectWithOrigin("/ws", "https://evil.example.com"));

        assertThat(failure).isInstanceOf(ExecutionException.class);
        assertThat(failure.getCause()).isInstanceOfSatisfying(WebSocketHandshakeException.class,
                exception -> assertThat(exception.getResponse().statusCode()).isEqualTo(403));
    }

    @Test
    void internalEventsRequireConfiguredToken() {
        ResponseEntity<String> response = restTemplate.postForEntity("/internal/gateway/events/public", Map.of(
                "type", "ticker.update",
                "market", "KRW-BTC",
                "data", Map.of("tradePrice", 1)
        ), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("UNAUTHORIZED");
    }

    private ResponseEntity<String> postInternal(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Mockcoin-Internal-Event-Token", "test-internal-token");
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private TestSocket connect(String path) throws Exception {
        return connect(path, null, null);
    }

    private TestSocket connectInternal(String path, String token) throws Exception {
        return connect(path, token, null);
    }

    private TestSocket connectWithOrigin(String path, String origin) throws Exception {
        return connect(path, null, origin);
    }

    private TestSocket connect(String path, String internalToken, String origin) throws Exception {
        TestSocket listener = new TestSocket();
        var builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3));
        if (internalToken != null) {
            builder.header("X-Mockcoin-Internal-Event-Token", internalToken);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        WebSocket webSocket = builder.buildAsync(URI.create("ws://127.0.0.1:" + port + path), listener)
                .get(3, TimeUnit.SECONDS);
        listener.bind(webSocket);
        return listener;
    }

    private static final class TestSocket implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private WebSocket webSocket;

        void bind(WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        void send(String text) {
            webSocket.sendText(text, true).join();
        }

        JsonNode nextJson(ObjectMapper objectMapper) throws Exception {
            String payload = messages.poll(3, TimeUnit.SECONDS);
            assertThat(payload).isNotNull();
            return objectMapper.readTree(payload);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messages.add(data.toString());
            webSocket.request(1);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return WebSocket.Listener.super.onPing(webSocket, message);
        }
    }

    private static final class TestUserBackend {
        private final HttpServer server;

        private TestUserBackend(HttpServer server) {
            this.server = server;
        }

        static TestUserBackend start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
                server.createContext("/api/v1/auth/me", TestUserBackend::handleMe);
                server.setExecutor(Executors.newSingleThreadExecutor());
                server.start();
                return new TestUserBackend(server);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to start test user backend", exception);
            }
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        void stop() {
            server.stop(0);
        }

        private static void handleMe(HttpExchange exchange) throws IOException {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer valid-token".equals(authorization)) {
                writeJson(exchange, 401, "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"UNAUTHORIZED\",\"reason\":\"인증이 필요합니다.\",\"data\":null}}");
                return;
            }
            writeJson(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"userId\":777},\"error\":null}");
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
