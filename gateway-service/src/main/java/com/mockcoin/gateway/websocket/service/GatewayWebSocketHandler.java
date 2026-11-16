package com.mockcoin.gateway.websocket.service;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.global.security.GatewayTokenAuthenticator;
import com.mockcoin.gateway.websocket.config.GatewayWebSocketProperties;
import com.mockcoin.gateway.websocket.domain.GatewayWebSocketCloseStatus;
import com.mockcoin.shared.api.ApiException;
import com.mockcoin.shared.api.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PreDestroy;

// /ws 세션의 연결/메시지/종료 처리를 담당하는 메인 WebSocket 핸들러
@Component
public class GatewayWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketHandler.class);
    private static final List<String> PUBLIC_CHANNELS = List.of("ticker", "orderbook", "candle", "marketList");
    private static final List<String> PRIVATE_CHANNELS = List.of("order", "portfolio");

    private final ObjectMapper objectMapper;
    private final GatewayWebSocketProperties properties;
    private final GatewayTokenAuthenticator tokenAuthenticator;
    private final GatewayWebSocketSessionRegistry sessions;
    private final Map<String, AuthDeadline> authDeadlines = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor authDeadlineExecutor;

    // auth 마감 처리용 단일 daemon 스케줄러를 만든다(취소 작업은 큐에서 빼고, 종료 뒤 남은 지연 작업은 실행하지 않는다).
    private static ScheduledThreadPoolExecutor createAuthDeadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "gateway-ws-auth-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    // ObjectMapper, WebSocket 설정, 토큰 인증기, 세션 레지스트리를 주입받고 auth 마감 스케줄러를 새로 만든다.
    @Autowired
    public GatewayWebSocketHandler(ObjectMapper objectMapper, GatewayWebSocketProperties properties, GatewayTokenAuthenticator tokenAuthenticator, GatewayWebSocketSessionRegistry sessions) {
        this(objectMapper, properties, tokenAuthenticator, sessions, createAuthDeadlineExecutor());
    }

    // 의존성과 auth 마감 스케줄러를 받는다.
    GatewayWebSocketHandler(
            ObjectMapper objectMapper,
            GatewayWebSocketProperties properties,
            GatewayTokenAuthenticator tokenAuthenticator,
            GatewayWebSocketSessionRegistry sessions,
            ScheduledThreadPoolExecutor authDeadlineExecutor
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.tokenAuthenticator = tokenAuthenticator;
        this.sessions = sessions;
        this.authDeadlineExecutor = authDeadlineExecutor;
    }

    // 세션 등록, 쿼리 토큰 인증, auth timeout 데드라인을 설정한다
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!sessions.register(session)) {
            return;
        }
        String token = queryToken(session.getUri());
        if (token != null) {
            authenticate(session, token);
        }
        GatewayWebSocketSessionRegistry.ClientSession client = sessions.find(session);
        if (session.isOpen() && client != null && !client.authenticated() && !client.hasPublicSubscriptions()) {
            scheduleAuthDeadline(session);
        }
    }

    // auth/subscribe/unsubscribe/pong 클라이언트 메시지를 라우팅하고 rate limit/size를 강제한다
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > properties.maxMessageBytes()) {
            sendError(session, "MESSAGE_TOO_LARGE", "메시지가 너무 큽니다.");
            cancelAuthDeadline(session);
            session.close(GatewayWebSocketCloseStatus.RATE_LIMITED);
            return;
        }
        GatewayWebSocketSessionRegistry.ClientSession client = sessions.find(session);
        if (client == null) {
            return;
        }
        if (!sessions.recordInboundMessage(client, Instant.now())) {
            sendError(session, "RATE_LIMITED", "요청이 너무 많습니다.");
            cancelAuthDeadline(session);
            session.close(GatewayWebSocketCloseStatus.RATE_LIMITED);
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String action = root.path("action").asText("");
            switch (action) {
                case "auth" -> authenticate(session, root.path("token").asText(null));
                case "subscribe" -> subscribe(session, root);
                case "unsubscribe" -> unsubscribe(session, root);
                case "pong" -> client.markPong();
                default -> sendError(session, "INVALID_ACTION", "지원하지 않는 WebSocket action입니다.");
            }
        } catch (ApiException exception) {
            sendError(session, exception.errorCode(), exception.getMessage());
        } catch (Exception exception) {
            // malformed JSON 등 예기치 못한 파싱 실패는 세션을 끊지 않고 INVALID_ACTION으로 응답한다.
            sendError(session, "INVALID_ACTION", "지원하지 않는 WebSocket action입니다.");
        }
    }

    // 연결이 닫히면 세션 레지스트리에서 구독 정보를 정리한다
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cancelAuthDeadline(session);
        sessions.unregister(session);
    }

    // 남은 auth 마감을 모두 취소하고 스케줄러를 즉시 종료한 뒤 최대 5초 기다린다.
    @PreDestroy
    void shutdownAuthDeadlineExecutor() {
        authDeadlines.values().forEach(AuthDeadline::cancel);
        authDeadlines.clear();
        authDeadlineExecutor.shutdownNow();
        try {
            if (!authDeadlineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Gateway WebSocket auth-deadline executor did not terminate within 5s");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    // 토큰으로 userId를 확인해 세션을 인증하고 auth.ok를 보낸다(이미 인증된 세션이면 에러만 보낸다).
    // 401이면 AUTH_REQUIRED로, 502·503이면 인증 서비스 불가로 세션을 닫는다.
    private void authenticate(WebSocketSession session, String token) throws IOException {
        GatewayWebSocketSessionRegistry.ClientSession client = sessions.get(session);
        if (client.authenticated()) {
            sendError(session, "ALREADY_AUTHENTICATED", "이미 인증된 세션입니다.");
            return;
        }
        try {
            Long userId = tokenAuthenticator.resolveUserId(token, client.ipAddress());
            if (!sessions.authenticate(client, userId)) {
                cancelAuthDeadline(session);
                return;
            }
            cancelAuthDeadline(session);
            send(session, Map.of("type", "auth.ok", "userId", userId, "ts", Instant.now().toEpochMilli()));
        } catch (ApiException exception) {
            if (exception.statusCode() == 401) {
                cancelAuthDeadline(session);
                sendError(session, "AUTH_REQUIRED", "인증이 필요합니다.");
                session.close(GatewayWebSocketCloseStatus.AUTH_REQUIRED);
                return;
            }
            sendError(session, exception.errorCode(), exception.getMessage());
            if (exception.statusCode() == 502 || exception.statusCode() == 503) {
                cancelAuthDeadline(session);
                session.close(GatewayWebSocketCloseStatus.AUTH_SERVICE_UNAVAILABLE);
            }
        }
    }

    // 채널·market·interval을 검증하고 구독 한도 안이면 구독한 뒤 ack를 보내며, public 구독이 생기면 auth 마감을 해제한다.
    private void subscribe(WebSocketSession session, JsonNode root) throws IOException {
        GatewayWebSocketSessionRegistry.ClientSession client = sessions.get(session);
        List<String> channels = channels(root.path("channels"));
        String interval = interval(root);
        validateSubscription(client, channels, root.path("market").asText(null), interval);
        String market = GatewayWebSocketSessionRegistry.normalizeMarket(root.path("market").asText(null));
        if (sessions.wouldExceedSubscriptionLimit(client, channels, market, interval)) {
            sendError(session, "SUBSCRIPTION_LIMIT", "구독 가능한 채널 수를 초과했습니다.");
            return;
        }
        client.subscribe(channels, market, interval);
        if (client.hasPublicSubscriptions()) {
            cancelAuthDeadline(session);
        }
        send(session, acknowledgement("subscribe.ok", channels, market, interval));
    }

    // 구독을 해제하고 ack를 보내며, 미인증 세션의 public 구독이 모두 사라지면 auth 마감을 다시 건다.
    private void unsubscribe(WebSocketSession session, JsonNode root) throws IOException {
        List<String> channels = channels(root.path("channels"));
        String interval = interval(root);
        String market = GatewayWebSocketSessionRegistry.normalizeMarket(root.path("market").asText(null));
        GatewayWebSocketSessionRegistry.ClientSession client = sessions.get(session);
        boolean hadPublicSubscriptions = client.hasPublicSubscriptions();
        client.unsubscribe(channels, market, interval);
        if (!client.authenticated() && hadPublicSubscriptions && !client.hasPublicSubscriptions()) {
            scheduleAuthDeadline(session);
        }
        send(session, acknowledgement("unsubscribe.ok", channels, market, interval));
    }

    // 지원 채널인지, private 채널은 인증됐는지, public 채널(marketList 제외)은 market이 있는지,
    // interval은 candle에만 쓰였는지 확인하고, 어기면 예외를 던진다.
    private static void validateSubscription(GatewayWebSocketSessionRegistry.ClientSession client, List<String> channels, String market, String interval) {
        for (String channel : channels) {
            if (!PUBLIC_CHANNELS.contains(channel) && !PRIVATE_CHANNELS.contains(channel)) {
                throw CommonErrorCode.INVALID_PARAMETER.exception("지원하지 않는 WebSocket channel입니다.");
            }
            if (PRIVATE_CHANNELS.contains(channel) && !client.authenticated()) {
                throw CommonErrorCode.UNAUTHORIZED.exception();
            }
            if (PUBLIC_CHANNELS.contains(channel) && !"marketList".equals(channel) && (market == null || market.isBlank())) {
                throw CommonErrorCode.INVALID_PARAMETER.exception("market은 필수입니다.");
            }
            if (interval != null && !"candle".equals(channel)) {
                throw CommonErrorCode.INVALID_PARAMETER.exception("interval 구독 필터는 candle 채널에서만 사용할 수 있습니다.");
            }
        }
    }

    // interval 값을 꺼내 trim하고, 없으면 null, 1m·5m·15m·1h·4h·1d가 아니면 INVALID_PARAMETER 예외를 던진다.
    private static String interval(JsonNode root) {
        JsonNode node = root.path("interval");
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String interval = node.asText(null);
        if (interval == null || interval.isBlank()) {
            return null;
        }
        String normalized = interval.trim();
        if (!List.of("1m", "5m", "15m", "1h", "4h", "1d").contains(normalized)) {
            throw CommonErrorCode.INVALID_PARAMETER.exception("지원하지 않는 candle interval입니다.");
        }
        return normalized;
    }

    // channels 배열을 문자열 목록으로 꺼내고, 비어 있으면 INVALID_PARAMETER 예외를 던진다.
    private static List<String> channels(JsonNode node) {
        List<String> channels = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> channels.add(item.asText()));
        }
        if (channels.isEmpty()) {
            throw CommonErrorCode.INVALID_PARAMETER.exception("channels는 필수입니다.");
        }
        return channels;
    }

    // 열린 세션이 미인증이고 public 구독도 없으면 AUTH_REQUIRED로 닫고, 처리 중 예외가 나면 세션을 등록 해제한다.
    private void closeIfUnauthenticated(WebSocketSession session) {
        try {
            if (!session.isOpen()) {
                return;
            }
            GatewayWebSocketSessionRegistry.ClientSession client = sessions.get(session);
            if (!client.authenticated() && !client.hasPublicSubscriptions()) {
                sendError(session, "AUTH_REQUIRED", "인증이 필요합니다.");
                session.close(GatewayWebSocketCloseStatus.AUTH_REQUIRED);
            }
        } catch (Exception ignored) {
            sessions.unregister(session);
        }
    }

    // 기존 마감을 취소하고, authTimeoutSeconds 뒤 미인증 세션을 닫는 마감을 새로 예약한다.
    private void scheduleAuthDeadline(WebSocketSession session) {
        AuthDeadline deadline = new AuthDeadline();
        AuthDeadline previous = authDeadlines.put(session.getId(), deadline);
        if (previous != null) {
            previous.cancel();
        }
        try {
            ScheduledFuture<?> future = authDeadlineExecutor.schedule(() -> {
                if (authDeadlines.remove(session.getId(), deadline)) {
                    closeIfUnauthenticated(session);
                }
            }, properties.authTimeoutSeconds(), TimeUnit.SECONDS);
            deadline.attach(future);
        } catch (RuntimeException exception) {
            authDeadlines.remove(session.getId(), deadline);
            throw exception;
        }
    }

    // 세션의 auth 마감을 제거하고 예약 작업을 취소한다.
    private void cancelAuthDeadline(WebSocketSession session) {
        AuthDeadline deadline = authDeadlines.remove(session.getId());
        if (deadline != null) {
            deadline.cancel();
        }
    }

    // 인증 마감 예약 작업 핸들: 예약 작업이 연결되기 전에 취소돼도 연결 즉시 취소되도록 취소 상태를 함께 보관한다
    private static final class AuthDeadline {
        private ScheduledFuture<?> future;
        private boolean cancelled;

        // 예약 작업을 연결하고, 그 사이 이미 취소된 마감이면 바로 취소한다.
        private synchronized void attach(ScheduledFuture<?> scheduledFuture) {
            future = scheduledFuture;
            if (cancelled) {
                scheduledFuture.cancel(false);
            }
        }

        // 취소 표시를 남기고, 연결된 예약 작업이 있으면 취소한다.
        private synchronized void cancel() {
            cancelled = true;
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    // 연결 URI의 token 쿼리 파라미터를 꺼내고, 없으면 null을 반환한다.
    private static String queryToken(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("token");
    }

    // type=error 메시지(code, message, ts)를 세션에 보낸다.
    private void sendError(WebSocketSession session, String code, String message) throws IOException {
        send(session, Map.of("type", "error", "code", code, "message", message, "ts", Instant.now().toEpochMilli()));
    }

    // 구독·해제 ack payload를 만들고, interval은 있을 때만 넣는다.
    private static Map<String, Object> acknowledgement(String type, List<String> channels, String market, String interval) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("channels", channels);
        payload.put("market", market);
        if (interval != null) {
            payload.put("interval", interval);
        }
        payload.put("ts", Instant.now().toEpochMilli());
        return payload;
    }

    private void send(WebSocketSession session, Map<String, Object> payload) throws IOException {
        sessions.send(session, payload);
    }
}
