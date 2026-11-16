package com.mockcoin.gateway.websocket.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.global.security.GatewaySecurityHardeningProperties;
import com.mockcoin.gateway.global.security.TrustedProxyResolver;
import com.mockcoin.gateway.websocket.config.GatewayWebSocketProperties;
import com.mockcoin.gateway.websocket.domain.GatewayWebSocketCloseStatus;
import com.mockcoin.observability.MockcoinMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

// WebSocket 세션 lifecycle을 조율하고 heartbeat ping/pong을 관리하는 레지스트리.
// admission control(연결 한도)은 WebSocketConnectionLimiter, 구독 라우팅은 WebSocketSubscriptionIndex에 위임한다.
@Component
public class GatewayWebSocketSessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketSessionRegistry.class);
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final GatewayWebSocketProperties properties;
    private final GatewaySecurityHardeningProperties securityProperties;
    private final MockcoinMetrics metrics;
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final WebSocketConnectionLimiter connectionLimiter = new WebSocketConnectionLimiter();
    private final WebSocketSubscriptionIndex subscriptionIndex = new WebSocketSubscriptionIndex();

    // ObjectMapper, WebSocket 설정, 보안 설정, 메트릭을 주입받는다.
    public GatewayWebSocketSessionRegistry(
            ObjectMapper objectMapper,
            GatewayWebSocketProperties properties,
            GatewaySecurityHardeningProperties securityProperties,
            MockcoinMetrics metrics
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.securityProperties = securityProperties;
        this.metrics = metrics;
    }

    // 연결 한도를 넘으면 CONNECTION_LIMIT로 닫고 false를, 통과하면 동시 전송에 안전한 데코레이터로 감싸 등록한다.
    boolean register(WebSocketSession session) {
        String ipAddress = clientIp(session);
        if (!connectionLimiter.tryAcquireConnection(
                ipAddress,
                properties.maxConnectionAttemptsPerMinute(),
                properties.maxGlobalConnections(),
                properties.maxConnectionsPerIp())) {
            closeQuietly(session, GatewayWebSocketCloseStatus.CONNECTION_LIMIT);
            return false;
        }
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session,
                SEND_TIME_LIMIT_MILLIS,
                SEND_BUFFER_SIZE_LIMIT_BYTES
        );
        safeSession.setTextMessageSizeLimit(properties.maxMessageBytes());
        sessions.put(session.getId(), new ClientSession(safeSession, ipAddress, this));
        metrics.wsConnected();
        return true;
    }

    // 등록된 세션을 한 번만 제거하고, 구독 인덱스와 연결 카운터를 함께 해제한다.
    void unregister(WebSocketSession session) {
        ClientSession registered = sessions.get(session.getId());
        if (registered == null) {
            return;
        }
        synchronized (registered) {
            if (!sessions.remove(session.getId(), registered)) {
                return;
            }
            registered.clearAllSubscriptions();
            connectionLimiter.releaseConnection(registered.ipAddress(), registered.userId());
            metrics.wsDisconnected();
        }
    }

    // 등록된 세션을 반환하고, 없으면 IllegalStateException을 던진다.
    ClientSession get(WebSocketSession session) {
        ClientSession client = find(session);
        if (client == null) {
            throw new IllegalStateException("WebSocket session is not registered");
        }
        return client;
    }

    ClientSession find(WebSocketSession session) {
        return sessions.get(session.getId());
    }

    List<ClientSession> publicSubscribers(String channel, String market, String interval) {
        return subscriptionIndex.publicSubscribers(channel, market, interval);
    }

    List<ClientSession> privateSubscribers(Long userId, String channel) {
        return subscriptionIndex.privateSubscribers(userId, channel);
    }

    void send(ClientSession client, Map<String, Object> payload) {
        sendSerialized(client.session(), serialize(payload));
    }

    void send(WebSocketSession session, Map<String, Object> payload) {
        sendSerialized(session, serialize(payload));
    }

    // 직렬화된 메시지를 열린 세션에 보내고(값이 없거나 닫혔으면 무시), 전송 실패는 IllegalStateException으로 던진다.
    void sendSerialized(WebSocketSession session, String serializedPayload) {
        if (serializedPayload == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(serializedPayload));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to send WebSocket message", exception);
        }
    }

    // payload를 JSON으로 직렬화하고, 실패하면 null을 반환한다.
    String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            return null;
        }
    }

    // 등록된 열린 미인증 세션에만 userId를 붙이고, 사용자별 연결 한도를 넘으면 CONNECTION_LIMIT로 닫고 false를 반환한다.
    boolean authenticate(ClientSession client, Long userId) {
        if (userId == null) {
            return false;
        }
        synchronized (client) {
            if (sessions.get(client.session().getId()) != client
                    || !client.session().isOpen()
                    || client.authenticated()) {
                return false;
            }
            if (!connectionLimiter.tryAcquireUser(userId, properties.maxConnectionsPerUser())) {
                closeQuietly(client.session(), GatewayWebSocketCloseStatus.CONNECTION_LIMIT);
                return false;
            }
            client.authenticate(userId);
            return true;
        }
    }

    // 세션의 1초 윈도우 수신 횟수를 올리고, 설정된 초당 메시지 한도 이내인지 반환한다.
    boolean recordInboundMessage(ClientSession client, Instant now) {
        return client.recordInboundMessage(now, properties.maxMessagesPerSecond());
    }

    // 이번 구독을 더하면 세션당 구독 한도를 넘는지 확인한다.
    boolean wouldExceedSubscriptionLimit(ClientSession client, List<String> channels, String market, String interval) {
        return client.subscriptionCountAfter(channels, market, interval) > properties.maxSubscriptionsPerSession();
    }

    // 주기적으로 모든 세션에 ping을 보낸다
    @Scheduled(fixedDelayString = "${mockcoin.websocket.ping-interval-seconds:30}000")
    public void scheduledPingTick() {
        sendPingTick(Instant.now());
    }

    // 주기적으로 ping 응답이 없는 세션을 점검해 임계치 초과 시 강제 종료한다
    @Scheduled(fixedDelayString = "${mockcoin.websocket.heartbeat-check-interval-millis:1000}")
    public void scheduledHeartbeatCheckTick() {
        heartbeatCheckTick(Instant.now());
    }

    // 만료된 연결 시도 기록을 정리하고 열린 세션마다 ping을 보내며, 닫혔거나 전송에 실패한 세션은 정리한다.
    void sendPingTick(Instant now) {
        connectionLimiter.purgeExpiredAttempts(now);
        for (ClientSession client : sessions.values()) {
            if (!client.session().isOpen()) {
                unregister(client.session());
                continue;
            }
            client.markPingSent(now);
            try {
                send(client, Map.of("type", "ping", "ts", now.toEpochMilli()));
            } catch (RuntimeException exception) {
                closeQuietly(client.session(), GatewayWebSocketCloseStatus.FORCE_CLOSED);
                unregister(client.session());
            }
        }
    }

    // pong 시간 초과가 허용 횟수에 닿은 세션은 강제 종료하고, 닫힌 세션은 등록 해제한다.
    void heartbeatCheckTick(Instant now) {
        for (ClientSession client : sessions.values()) {
            if (!client.session().isOpen()) {
                unregister(client.session());
                continue;
            }
            if (client.registerMissedPongIfTimedOut(now, Duration.ofSeconds(properties.pongTimeoutSeconds()), properties.maxMissedPongs())) {
                closeQuietly(client.session(), GatewayWebSocketCloseStatus.FORCE_CLOSED);
                unregister(client.session());
            }
        }
    }

    // market을 대문자로 바꾸고, 비어 있으면 null을 반환한다.
    static String normalizeMarket(String market) {
        return market == null || market.isBlank() ? null : market.toUpperCase();
    }

    // 핸드셰이크의 X-Forwarded-For와 신뢰 프록시 설정으로 실제 클라이언트 IP를 구한다.
    private String clientIp(WebSocketSession session) {
        String remoteAddr = directRemoteAddress(session);
        HttpHeaders headers = session.getHandshakeHeaders();
        String forwarded = headers == null ? null : headers.getFirst("X-Forwarded-For");
        return TrustedProxyResolver.resolveClientIp(
                remoteAddr,
                forwarded,
                securityProperties.getTrustedProxyCidrs()
        );
    }

    // 직접 연결한 peer 주소를 반환하고, 알 수 없으면 "unknown"을 반환한다.
    private static String directRemoteAddress(WebSocketSession session) {
        InetSocketAddress remoteAddress = session.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    // 열린 세션을 주어진 상태로 닫고, IOException은 debug 로그만 남긴다.
    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException exception) {
            log.debug("Failed to close WebSocket session id={} status={}", session.getId(), status, exception);
        }
    }

    // 등록된 WebSocket 세션 상태(IP, 인증 userId, 구독 키, pong 대기·수신율 추적)
    public static final class ClientSession {
        private final WebSocketSession session;
        private final String ipAddress;
        private final GatewayWebSocketSessionRegistry registry;
        private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        private volatile Long userId;
        private volatile Instant awaitingPongSince;
        private volatile int missedPongs;
        private volatile Instant inboundWindowStart = Instant.now();
        private final AtomicInteger inboundWindowCount = new AtomicInteger();

        // 데코레이터로 감싼 세션, 클라이언트 IP, 소속 레지스트리를 받는다.
        private ClientSession(WebSocketSession session, String ipAddress, GatewayWebSocketSessionRegistry registry) {
            this.session = session;
            this.ipAddress = ipAddress;
            this.registry = registry;
        }

        WebSocketSession session() {
            return session;
        }

        Long userId() {
            return userId;
        }

        String ipAddress() {
            return ipAddress;
        }

        void authenticate(Long userId) {
            this.userId = userId;
        }

        boolean authenticated() {
            return userId != null;
        }

        // pong을 받았으므로 pong 대기 시각과 놓친 pong 수를 초기화한다.
        void markPong() {
            awaitingPongSince = null;
            missedPongs = 0;
        }

        // 채널별 구독 키를 만들어 새로 추가된 키만 구독 인덱스에 등록한다.
        void subscribe(List<String> channels, String market, String interval) {
            for (String channel : channels) {
                String key = keyFor(channel, market, interval);
                if (subscriptions.add(key)) {
                    registry.subscriptionIndex.register(this, key);
                }
            }
        }

        // 이번 구독을 더했을 때의 구독 키 수(중복 제외)를 계산한다.
        int subscriptionCountAfter(List<String> channels, String market, String interval) {
            Set<String> next = new HashSet<>(subscriptions);
            for (String channel : channels) {
                next.add(keyFor(channel, market, interval));
            }
            return next.size();
        }

        // 1초 윈도우의 수신 메시지 수를 올리고(1초가 지나면 초기화), 초당 한도 이내인지 반환한다.
        boolean recordInboundMessage(Instant now, int maxMessagesPerSecond) {
            if (Duration.between(inboundWindowStart, now).toMillis() >= 1_000) {
                inboundWindowStart = now;
                inboundWindowCount.set(0);
            }
            return inboundWindowCount.incrementAndGet() <= maxMessagesPerSecond;
        }

        // 이미 pong을 기다리는 중이 아니면 ping을 보낸 시각을 기록한다.
        void markPingSent(Instant now) {
            if (awaitingPongSince == null) {
                awaitingPongSince = now;
            }
        }

        // pong 대기가 timeout을 넘으면 놓친 횟수를 올리고 대기를 다시 시작하며, 허용 횟수에 닿았는지 반환한다.
        boolean registerMissedPongIfTimedOut(Instant now, Duration timeout, int maxMissedPongs) {
            Instant awaitingSince = awaitingPongSince;
            if (awaitingSince == null || Duration.between(awaitingSince, now).compareTo(timeout) <= 0) {
                return false;
            }
            missedPongs += 1;
            awaitingPongSince = now;
            return missedPongs >= maxMissedPongs;
        }

        // 채널별 구독 키 중 실제로 있던 키만 제거하고 구독 인덱스에서도 해제한다.
        void unsubscribe(List<String> channels, String market, String interval) {
            for (String channel : channels) {
                String key = keyFor(channel, market, interval);
                if (subscriptions.remove(key)) {
                    registry.subscriptionIndex.unregister(this, key);
                }
            }
        }

        // private가 아닌 채널 구독이 하나라도 있는지 확인한다.
        boolean hasPublicSubscriptions() {
            return subscriptions.stream().anyMatch(key -> !isPrivate(channelOf(key)));
        }

        // 모든 구독 키를 구독 인덱스에서 해제하고 비운다.
        void clearAllSubscriptions() {
            for (String key : subscriptions) {
                registry.subscriptionIndex.unregister(this, key);
            }
            subscriptions.clear();
        }

        // private 채널은 사용자 키(userId가 없으면 catch-all 키)로, public 채널은 market·interval 키로 구독 키를 만든다.
        private String keyFor(String channel, String market, String interval) {
            if (isPrivate(channel)) {
                // 인증된 세션만 private 채널을 구독할 수 있도록 handler에서 강제하므로 userId는 non-null이다.
                // 안전망으로 userId가 비면 기존 catch-all 키로 폴백한다.
                Long uid = userId;
                return uid == null
                        ? WebSocketSubscriptionIndex.subscriptionKey(channel, null)
                        : WebSocketSubscriptionIndex.privateSubscriptionKey(channel, uid);
            }
            return WebSocketSubscriptionIndex.subscriptionKey(channel, "marketList".equals(channel) ? null : market, interval);
        }

        // 구독 키에서 첫 ':' 앞의 채널 이름을 꺼낸다.
        private static String channelOf(String key) {
            int delimiter = key.indexOf(':');
            return delimiter < 0 ? key : key.substring(0, delimiter);
        }

        // order·portfolio 같은 private 채널인지 확인한다.
        static boolean isPrivate(String channel) {
            return "order".equals(channel) || "portfolio".equals(channel);
        }
    }
}
