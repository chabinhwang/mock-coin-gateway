package com.mockcoin.gateway.websocket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.global.security.GatewaySecurityHardeningProperties;
import com.mockcoin.gateway.global.security.GatewayTokenAuthenticator;
import com.mockcoin.gateway.websocket.config.GatewayWebSocketProperties;
import com.mockcoin.gateway.websocket.domain.PublicGatewayEvent;
import com.mockcoin.gateway.websocket.domain.GatewayWebSocketCloseStatus;
import com.mockcoin.observability.MockcoinMetrics;
import com.mockcoin.shared.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GatewayWebSocketHardeningTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockcoinMetrics metrics() {
        return new MockcoinMetrics(new SimpleMeterRegistry());
    }

    @Test
    void registryRejectsGlobalAndIpConnectionLimits() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, properties(1, 1, 10, 30, 30, 50), securityProps(), metrics());
        TestSession first = new TestSession("s1", "10.0.0.1");
        TestSession second = new TestSession("s2", "10.0.0.2");

        assertThat(registry.register(first)).isTrue();
        assertThat(registry.register(second)).isFalse();
        assertThat(second.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.CONNECTION_LIMIT);

        registry.unregister(first);
        TestSession third = new TestSession("s3", "10.0.0.1");
        TestSession fourth = new TestSession("s4", "10.0.0.1");
        assertThat(registry.register(third)).isTrue();
        assertThat(registry.register(fourth)).isFalse();
        assertThat(fourth.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.CONNECTION_LIMIT);
    }

    @Test
    void registryUsesRemoteAddressInsteadOfSpoofableForwardedForByDefault() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, properties(10, 1, 10, 30, 30, 50), securityProps(), metrics());
        TestSession first = new TestSession("s1", "10.0.0.1", "203.0.113.1");
        TestSession second = new TestSession("s2", "10.0.0.1", "203.0.113.2");

        assertThat(registry.register(first)).isTrue();
        assertThat(first.getTextMessageSizeLimit()).isEqualTo(4096);
        assertThat(registry.register(second)).isFalse();

        assertThat(second.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.CONNECTION_LIMIT);
    }

    @Test
    void registryRejectsIpConnectionAttemptLimit() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, properties(10, 10, 10, 30, 1, 50), securityProps(), metrics());
        TestSession first = new TestSession("s1", "10.0.0.1");
        TestSession second = new TestSession("s2", "10.0.0.1");

        assertThat(registry.register(first)).isTrue();
        registry.unregister(first);
        assertThat(registry.register(second)).isFalse();

        assertThat(second.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.CONNECTION_LIMIT);
    }

    @Test
    void registryRejectsTooManySessionsForSameAuthenticatedUser() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, properties(10, 10, 1, 30, 30, 50), securityProps(), metrics());
        TestSession first = new TestSession("s1", "10.0.0.1");
        TestSession second = new TestSession("s2", "10.0.0.2");
        registry.register(first);
        registry.register(second);

        assertThat(registry.authenticate(registry.get(first), 777L)).isTrue();
        assertThat(registry.authenticate(registry.get(second), 777L)).isFalse();

        assertThat(second.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.CONNECTION_LIMIT);
    }

    @Test
    void detachedSessionCannotAcquireOrLeakAuthenticatedUserSlot() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(
                objectMapper,
                properties(10, 10, 1, 30, 30, 50),
                securityProps(),
                metrics()
        );
        TestSession detached = new TestSession("detached", "10.0.0.1");
        TestSession replacement = new TestSession("replacement", "10.0.0.2");
        registry.register(detached);
        GatewayWebSocketSessionRegistry.ClientSession staleClient = registry.get(detached);
        registry.unregister(detached);

        assertThat(registry.authenticate(staleClient, 777L)).isFalse();
        assertThat(registry.register(replacement)).isTrue();
        assertThat(registry.authenticate(registry.get(replacement), 777L)).isTrue();
    }

    @Test
    void heartbeatClosesAfterConfiguredMissedPongsAndPongResetsCounter() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, properties(10, 10, 5, 30, 30, 50), securityProps(), metrics());
        TestSession session = new TestSession("s1", "10.0.0.1");
        registry.register(session);
        Instant now = Instant.parse("2026-04-25T00:00:00Z");

        registry.sendPingTick(now);
        registry.heartbeatCheckTick(now.plusSeconds(11));
        assertThat(session.open).isTrue();
        registry.get(session).markPong();
        registry.sendPingTick(now.plusSeconds(12));
        registry.heartbeatCheckTick(now.plusSeconds(23));
        assertThat(session.open).isTrue();
        registry.heartbeatCheckTick(now.plusSeconds(34));

        assertThat(session.open).isFalse();
        assertThat(session.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.FORCE_CLOSED);
    }

    @Test
    void handlerRateLimitsInboundMessagesBeforeParsing() throws Exception {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, properties(10, 10, 5, 1, 30, 50), securityProps(), metrics());
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(objectMapper, properties(10, 10, 5, 1, 30, 50), mock(GatewayTokenAuthenticator.class), registry);
        TestSession session = new TestSession("s1", "10.0.0.1");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"action\":\"pong\"}"));
        handler.handleTextMessage(session, new TextMessage("not-json-but-rate-limited-first"));

        assertThat(session.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.RATE_LIMITED);
        assertThat(session.sentText()).anySatisfy(payload -> assertThat(payload).contains("RATE_LIMITED"));
    }

    @Test
    void handlerIgnoresLateMessageAfterSessionUnregistered() throws Exception {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, properties(10, 10, 5, 30, 30, 50), securityProps(), metrics());
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(objectMapper, properties(10, 10, 5, 30, 30, 50), mock(GatewayTokenAuthenticator.class), registry);
        TestSession session = new TestSession("s1", "10.0.0.1");
        handler.afterConnectionEstablished(session);
        registry.unregister(session);

        assertThatCode(() -> handler.handleTextMessage(session, new TextMessage("{\"action\":\"pong\"}")))
                .doesNotThrowAnyException();
        assertThat(session.sentText()).isEmpty();
    }

    @Test
    void handlerRejectsSubscriptionLimitWithoutClosingSession() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 1);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, props, securityProps(), metrics());
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(objectMapper, props, mock(GatewayTokenAuthenticator.class), registry);
        TestSession session = new TestSession("s1", "10.0.0.1");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("{\"action\":\"subscribe\",\"channels\":[\"ticker\"],\"market\":\"KRW-BTC\"}"));
        handler.handleTextMessage(session, new TextMessage("{\"action\":\"subscribe\",\"channels\":[\"orderbook\"],\"market\":\"KRW-BTC\"}"));

        assertThat(session.open).isTrue();
        assertThat(session.sentText()).anySatisfy(payload -> assertThat(payload).contains("SUBSCRIPTION_LIMIT"));
    }

    @Test
    void handlerKeepsUnauthenticatedPublicOnlySubscriptionAfterAuthDeadline() throws Exception {
        GatewayWebSocketProperties props = new GatewayWebSocketProperties(
                1, 4096, "token", List.of("*"), 30, 10, 1_000, 2,
                10, 10, 5, 30, 30, 50, 64
        );
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, props, securityProps(), metrics());
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(objectMapper, props, mock(GatewayTokenAuthenticator.class), registry);
        TestSession session = new TestSession("public-only", "10.0.0.1");
        try {
            handler.afterConnectionEstablished(session);
            handler.handleTextMessage(session, new TextMessage("{\"action\":\"subscribe\",\"channels\":[\"ticker\"],\"market\":\"KRW-BTC\"}"));

            assertThat(pendingAuthDeadlineCount(handler)).isZero();
            assertThat(queuedAuthDeadlineCount(handler)).isZero();

            Thread.sleep(1_200);

            assertThat(session.open).isTrue();
            assertThat(session.sentText()).anySatisfy(payload -> assertThat(payload).contains("subscribe.ok"));
        } finally {
            handler.shutdownAuthDeadlineExecutor();
        }
    }

    @Test
    void handlerRestoresAuthenticationDeadlineAfterLastPublicSubscriptionIsRemoved() throws Exception {
        GatewayWebSocketProperties props = new GatewayWebSocketProperties(
                1, 4096, "token", List.of("*"), 30, 10, 1_000, 2,
                10, 10, 5, 30, 30, 50, 64
        );
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(
                objectMapper,
                props,
                securityProps(),
                metrics()
        );
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(
                objectMapper,
                props,
                mock(GatewayTokenAuthenticator.class),
                registry
        );
        TestSession session = new TestSession("public-unsubscribed", "10.0.0.1");
        try {
            handler.afterConnectionEstablished(session);
            handler.handleTextMessage(session, new TextMessage(
                    "{\"action\":\"subscribe\",\"channels\":[\"ticker\"],\"market\":\"KRW-BTC\"}"
            ));
            handler.handleTextMessage(session, new TextMessage(
                    "{\"action\":\"unsubscribe\",\"channels\":[\"ticker\"],\"market\":\"KRW-BTC\"}"
            ));

            assertThat(pendingAuthDeadlineCount(handler)).isEqualTo(1);

            Thread.sleep(1_200);

            assertThat(session.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.AUTH_REQUIRED);
            assertThat(session.sentText()).anySatisfy(payload -> assertThat(payload).contains("AUTH_REQUIRED"));
        } finally {
            handler.shutdownAuthDeadlineExecutor();
        }
    }

    @Test
    void unsubscribeWithoutPublicSubscriptionCannotExtendAuthenticationDeadline() throws Exception {
        GatewayWebSocketProperties props = new GatewayWebSocketProperties(
                1, 4096, "token", List.of("*"), 30, 10, 1_000, 2,
                10, 10, 5, 30, 30, 50, 64
        );
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(
                objectMapper,
                props,
                securityProps(),
                metrics()
        );
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(
                objectMapper,
                props,
                mock(GatewayTokenAuthenticator.class),
                registry
        );
        TestSession session = new TestSession("unsubscribe-without-subscription", "10.0.0.1");
        try {
            handler.afterConnectionEstablished(session);
            Thread.sleep(650);
            handler.handleTextMessage(session, new TextMessage(
                    "{\"action\":\"unsubscribe\",\"channels\":[\"ticker\"],\"market\":\"KRW-BTC\"}"
            ));
            Thread.sleep(550);

            assertThat(session.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.AUTH_REQUIRED);
        } finally {
            handler.shutdownAuthDeadlineExecutor();
        }
    }

    @Test
    void cancelledOldAuthenticationDeadlineCannotRemoveOrFireReplacementDeadline() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(
                objectMapper,
                props,
                securityProps(),
                metrics()
        );
        CapturingDeadlineExecutor deadlines = new CapturingDeadlineExecutor();
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(
                objectMapper,
                props,
                mock(GatewayTokenAuthenticator.class),
                registry,
                deadlines
        );
        TestSession session = new TestSession("deadline-generation", "10.0.0.1");
        try {
            handler.afterConnectionEstablished(session);
            handler.handleTextMessage(session, new TextMessage(
                    "{\"action\":\"subscribe\",\"channels\":[\"ticker\"],\"market\":\"KRW-BTC\"}"
            ));
            handler.handleTextMessage(session, new TextMessage(
                    "{\"action\":\"unsubscribe\",\"channels\":[\"ticker\"],\"market\":\"KRW-BTC\"}"
            ));
            assertThat(deadlines.taskCount()).isEqualTo(2);
            assertThat(pendingAuthDeadlineCount(handler)).isEqualTo(1);

            deadlines.runTask(0);

            assertThat(session.open).isTrue();
            assertThat(pendingAuthDeadlineCount(handler)).isEqualTo(1);
        } finally {
            handler.shutdownAuthDeadlineExecutor();
        }
    }

    @Test
    void registerWrapsSessionInConcurrentDecoratorAndAppliesMessageSizeLimit() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, properties(10, 10, 5, 30, 30, 50), securityProps(), metrics());
        TestSession underlying = new TestSession("s1", "10.0.0.1");

        assertThat(registry.register(underlying)).isTrue();

        GatewayWebSocketSessionRegistry.ClientSession client = registry.get(underlying);
        assertThat(client.session())
                .as("registered session must be wrapped in ConcurrentWebSocketSessionDecorator")
                .isInstanceOf(org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator.class);
        assertThat(underlying.getTextMessageSizeLimit()).isEqualTo(4096);
    }

    @Test
    void authActionPassesResolvedClientIpToTokenAuthenticator() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(
                objectMapper,
                props,
                securityProps(List.of("10.0.0.0/8")),
                metrics()
        );
        GatewayTokenAuthenticator authenticator = mock(GatewayTokenAuthenticator.class);
        when(authenticator.resolveUserId("access-token", "203.0.113.7")).thenReturn(777L);
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(objectMapper, props, authenticator, registry);
        TestSession session = new TestSession("auth-action", "10.0.0.50", "203.0.113.7");
        try {
            handler.afterConnectionEstablished(session);
            handler.handleTextMessage(session, new TextMessage("{\"action\":\"auth\",\"token\":\"access-token\"}"));

            verify(authenticator).resolveUserId("access-token", "203.0.113.7");
            assertThat(session.open).isTrue();
            assertThat(session.sentText()).anySatisfy(payload -> assertThat(payload).contains("auth.ok"));
            assertThat(pendingAuthDeadlineCount(handler)).isZero();
            assertThat(queuedAuthDeadlineCount(handler)).isZero();
        } finally {
            handler.shutdownAuthDeadlineExecutor();
        }
    }

    @Test
    void unauthorizedAuthActionKeepsExistingAuthRequiredCloseContract() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, props, securityProps(), metrics());
        GatewayTokenAuthenticator authenticator = mock(GatewayTokenAuthenticator.class);
        when(authenticator.resolveUserId("bad-token", "10.0.0.1"))
                .thenThrow(new ApiException(401, "UNAUTHORIZED", "인증이 필요합니다."));
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(objectMapper, props, authenticator, registry);
        TestSession session = new TestSession("auth-401", "10.0.0.1");
        try {
            handler.afterConnectionEstablished(session);
            handler.handleTextMessage(session, new TextMessage("{\"action\":\"auth\",\"token\":\"bad-token\"}"));

            assertThat(session.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.AUTH_REQUIRED);
            assertThat(session.sentText()).anySatisfy(payload -> assertThat(payload).contains("AUTH_REQUIRED"));
        } finally {
            handler.shutdownAuthDeadlineExecutor();
        }
    }

    @Test
    void queryTokenAuthServiceFailurePreservesErrorAndUsesRetryableClose() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, props, securityProps(), metrics());
        GatewayTokenAuthenticator authenticator = mock(GatewayTokenAuthenticator.class);
        when(authenticator.resolveUserId("query-token", "10.0.0.2"))
                .thenThrow(new ApiException(503, "AUTH_SERVICE_UNAVAILABLE",
                        "인증 서비스를 일시적으로 사용할 수 없습니다."
                ));
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(objectMapper, props, authenticator, registry);
        TestSession session = new TestSession(
                "auth-503",
                "10.0.0.2",
                "10.0.0.2",
                URI.create("ws://localhost/ws?token=query-token")
        );
        try {
            handler.afterConnectionEstablished(session);

            verify(authenticator).resolveUserId("query-token", "10.0.0.2");
            assertThat(session.closeStatus).isEqualTo(GatewayWebSocketCloseStatus.AUTH_SERVICE_UNAVAILABLE);
            assertThat(session.sentText()).anySatisfy(payload -> assertThat(payload).contains("AUTH_SERVICE_UNAVAILABLE"));
            assertThat(pendingAuthDeadlineCount(handler)).isZero();
            assertThat(queuedAuthDeadlineCount(handler)).isZero();
        } finally {
            handler.shutdownAuthDeadlineExecutor();
        }
    }

    @Test
    void connectionCloseCancelsPendingAuthenticationDeadline() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, props, securityProps(), metrics());
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(
                objectMapper,
                props,
                mock(GatewayTokenAuthenticator.class),
                registry
        );
        TestSession session = new TestSession("closed-before-auth", "10.0.0.1");
        try {
            handler.afterConnectionEstablished(session);
            assertThat(pendingAuthDeadlineCount(handler)).isEqualTo(1);

            handler.afterConnectionClosed(session, CloseStatus.NORMAL);

            assertThat(pendingAuthDeadlineCount(handler)).isZero();
            assertThat(queuedAuthDeadlineCount(handler)).isZero();
        } finally {
            handler.shutdownAuthDeadlineExecutor();
        }
    }

    @Test
    void broadcastSerializesPayloadOnceAndDeliversSameStringToEverySubscriber() {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50);
        CountingObjectMapper countingMapper = new CountingObjectMapper();
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(countingMapper, props, securityProps(), metrics());
        GatewayWebSocketBroadcaster broadcaster = new GatewayWebSocketBroadcaster(registry, props, metrics());
        TestSession a = new TestSession("a", "10.0.0.1");
        TestSession b = new TestSession("b", "10.0.0.2");
        TestSession c = new TestSession("c", "10.0.0.3");
        registry.register(a);
        registry.register(b);
        registry.register(c);
        registry.get(a).subscribe(List.of("ticker"), "KRW-BTC", null);
        registry.get(b).subscribe(List.of("ticker"), "KRW-BTC", null);
        registry.get(c).subscribe(List.of("ticker"), "KRW-BTC", null);

        countingMapper.resetCount();
        int delivered = broadcaster.broadcastPublic(new PublicGatewayEvent(
                "ticker.update", "KRW-BTC", Instant.parse("2026-04-25T00:00:00Z"), Map.of("tradePrice", 7)));

        assertThat(delivered).isEqualTo(3);
        assertThat(countingMapper.serializeCount()).isEqualTo(1);
        assertThat(a.sentText()).hasSize(1);
        assertThat(b.sentText()).hasSize(1);
        assertThat(c.sentText()).hasSize(1);
        assertThat(a.sentText().get(0)).isEqualTo(b.sentText().get(0));
        assertThat(b.sentText().get(0)).isEqualTo(c.sentText().get(0));
    }

    @Test
    void marketListWirePayloadRemainsGlobalAndOmitsMarketField() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, props, securityProps(), metrics());
        GatewayWebSocketBroadcaster broadcaster = new GatewayWebSocketBroadcaster(registry, props, metrics());
        TestSession session = new TestSession("market-list", "10.0.0.1");
        registry.register(session);
        registry.get(session).subscribe(List.of("marketList"), null, null);

        int delivered = broadcaster.broadcastPublic(new PublicGatewayEvent(
                "marketList.update",
                null,
                Instant.parse("2026-04-25T00:00:00Z"),
                Map.of("markets", List.of(Map.of("market", "KRW-BTC", "currentPrice", 100)))
        ));

        assertThat(delivered).isEqualTo(1);
        var payload = objectMapper.readTree(session.sentText().getFirst());
        assertThat(payload.path("type").asText()).isEqualTo("marketList.update");
        assertThat(payload.has("market")).isFalse();
    }

    @Test
    void fanoutConcurrencyCapsConcurrentSendsAcrossBroadcasts() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50, 2);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, props, securityProps(), metrics());
        GatewayWebSocketBroadcaster broadcaster = new GatewayWebSocketBroadcaster(registry, props, metrics());
        SendConcurrencyProbe probe = new SendConcurrencyProbe(2);
        List<ConcurrencyTrackingSession> bitcoinSessions = List.of(
                new ConcurrencyTrackingSession("btc-1", "10.0.0.1", probe),
                new ConcurrencyTrackingSession("btc-2", "10.0.0.2", probe)
        );
        List<ConcurrencyTrackingSession> etherSessions = List.of(
                new ConcurrencyTrackingSession("eth-1", "10.0.0.3", probe),
                new ConcurrencyTrackingSession("eth-2", "10.0.0.4", probe)
        );
        bitcoinSessions.forEach(session -> {
            registry.register(session);
            registry.get(session).subscribe(List.of("ticker"), "KRW-BTC", null);
        });
        etherSessions.forEach(session -> {
            registry.register(session);
            registry.get(session).subscribe(List.of("ticker"), "KRW-ETH", null);
        });

        ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
        Future<Integer> bitcoin = calls.submit(() -> broadcaster.broadcastPublic(new PublicGatewayEvent(
                "ticker.update", "KRW-BTC", Instant.now(), Map.of("tradePrice", 1))));
        Future<Integer> ether = calls.submit(() -> broadcaster.broadcastPublic(new PublicGatewayEvent(
                "ticker.update", "KRW-ETH", Instant.now(), Map.of("tradePrice", 2))));
        try {
            assertThat(probe.awaitConfiguredConcurrency()).isTrue();
            Thread.sleep(100);
            assertThat(probe.maxActive()).isLessThanOrEqualTo(2);
        } finally {
            probe.release();
        }

        assertThat(bitcoin.get(2, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(ether.get(2, TimeUnit.SECONDS)).isEqualTo(2);
        calls.shutdownNow();
        broadcaster.shutdown();
    }

    @Test
    void slowSessionDoesNotBlockBroadcastOfOtherSessions() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, props, securityProps(), metrics());
        GatewayWebSocketBroadcaster broadcaster = new GatewayWebSocketBroadcaster(registry, props, metrics());
        TestSession fast = new TestSession("fast", "10.0.0.1");
        // 50초 sleep — broadcast가 slow client를 기다리지 않음을 검증.
        SlowSession slow = new SlowSession("slow", "10.0.0.2", java.time.Duration.ofSeconds(50));
        registry.register(fast);
        registry.register(slow);
        registry.get(fast).subscribe(List.of("ticker"), "KRW-BTC", null);
        registry.get(slow).subscribe(List.of("ticker"), "KRW-BTC", null);

        long start = System.nanoTime();
        int delivered = broadcaster.broadcastPublic(new PublicGatewayEvent(
                "ticker.update", "KRW-BTC", Instant.parse("2026-04-25T00:00:00Z"), Map.of("tradePrice", 1)));
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // fast는 거의 즉시 도달, slow는 자기 VT에서 sleep 중 — broadcast 호출은 short timeout으로 종료.
        assertThat(elapsedMs)
                .as("broadcast must not block longer than the bounded fanout timeout")
                .isLessThan(5_000);
        assertThat(delivered).isGreaterThanOrEqualTo(1);
        assertThat(fast.sentText()).hasSize(1);
    }

    @Test
    void completedSendsRemainCountedWhenSameFanoutWorkerLaterTimesOut() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50, 1);
        GatewayWebSocketSessionRegistry registry = mock(GatewayWebSocketSessionRegistry.class);
        GatewayWebSocketSessionRegistry.ClientSession fast = mock(GatewayWebSocketSessionRegistry.ClientSession.class);
        GatewayWebSocketSessionRegistry.ClientSession slow = mock(GatewayWebSocketSessionRegistry.ClientSession.class);
        WebSocketSession fastSession = mock(WebSocketSession.class);
        WebSocketSession slowSession = mock(WebSocketSession.class);
        MockcoinMetrics metrics = mock(MockcoinMetrics.class);
        when(registry.publicSubscribers("ticker", "KRW-BTC", null)).thenReturn(List.of(fast, slow));
        when(registry.serialize(org.mockito.ArgumentMatchers.anyMap())).thenReturn("payload");
        when(fast.session()).thenReturn(fastSession);
        when(slow.session()).thenReturn(slowSession);
        AtomicInteger sendOrder = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (sendOrder.getAndIncrement() == 0) {
                return null;
            }
            try {
                Thread.sleep(50_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fanout interrupted", exception);
            }
            return null;
        }).when(registry).sendSerialized(
                org.mockito.ArgumentMatchers.any(WebSocketSession.class),
                org.mockito.ArgumentMatchers.eq("payload")
        );
        GatewayWebSocketBroadcaster broadcaster = new GatewayWebSocketBroadcaster(registry, props, metrics);
        try {
            int delivered = broadcaster.broadcastPublic(new PublicGatewayEvent(
                    "ticker.update",
                    "KRW-BTC",
                    Instant.parse("2026-04-25T00:00:00Z"),
                    Map.of("tradePrice", 1)
            ));

            assertThat(delivered).isEqualTo(1);
            assertThat(sendOrder).hasValue(2);
        } finally {
            broadcaster.shutdown();
        }
    }

    @Test
    void publicSubscribersUsesInvertedIndexAndExcludesUnsubscribedSessions() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, properties(10, 10, 5, 30, 30, 50), securityProps(), metrics());
        TestSession a = new TestSession("a", "10.0.0.1");
        TestSession b = new TestSession("b", "10.0.0.2");
        TestSession c = new TestSession("c", "10.0.0.3");
        registry.register(a);
        registry.register(b);
        registry.register(c);
        registry.get(a).subscribe(List.of("ticker"), "KRW-BTC", null);
        registry.get(b).subscribe(List.of("ticker"), "KRW-ETH", null);
        registry.get(c).subscribe(List.of("orderbook"), "KRW-BTC", null);

        List<GatewayWebSocketSessionRegistry.ClientSession> btcTicker = registry.publicSubscribers("ticker", "KRW-BTC", null);
        assertThat(btcTicker).extracting(GatewayWebSocketSessionRegistry.ClientSession::session)
                .containsExactly(registry.get(a).session());

        registry.get(a).unsubscribe(List.of("ticker"), "KRW-BTC", null);
        assertThat(registry.publicSubscribers("ticker", "KRW-BTC", null)).isEmpty();

        registry.get(b).subscribe(List.of("ticker"), "KRW-BTC", null);
        registry.unregister(b);
        assertThat(registry.publicSubscribers("ticker", "KRW-BTC", null)).isEmpty();
    }

    @Test
    void candleSubscribersCanBeScopedByIntervalWithWildcardFallback() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, properties(10, 10, 5, 30, 30, 50), securityProps(), metrics());
        TestSession wildcard = new TestSession("wildcard", "10.0.0.1");
        TestSession oneMinute = new TestSession("oneMinute", "10.0.0.2");
        TestSession fiveMinute = new TestSession("fiveMinute", "10.0.0.3");
        registry.register(wildcard);
        registry.register(oneMinute);
        registry.register(fiveMinute);
        registry.get(wildcard).subscribe(List.of("candle"), "KRW-BTC", null);
        registry.get(oneMinute).subscribe(List.of("candle"), "KRW-BTC", "1m");
        registry.get(fiveMinute).subscribe(List.of("candle"), "KRW-BTC", "5m");

        assertThat(registry.publicSubscribers("candle", "KRW-BTC", "1m"))
                .extracting(GatewayWebSocketSessionRegistry.ClientSession::session)
                .containsExactlyInAnyOrder(registry.get(wildcard).session(), registry.get(oneMinute).session());
        assertThat(registry.publicSubscribers("candle", "KRW-BTC", "5m"))
                .extracting(GatewayWebSocketSessionRegistry.ClientSession::session)
                .containsExactlyInAnyOrder(registry.get(wildcard).session(), registry.get(fiveMinute).session());
        assertThat(registry.publicSubscribers("candle", "KRW-BTC", "15m"))
                .extracting(GatewayWebSocketSessionRegistry.ClientSession::session)
                .containsExactly(registry.get(wildcard).session());
    }

    @Test
    void registryHonorsForwardedForOnlyWhenRemoteIsTrustedProxy() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(
                objectMapper,
                properties(10, 1, 10, 30, 30, 50),
                securityProps(List.of("10.0.0.0/8")),
                metrics()
        );
        // 두 세션 모두 같은 trusted proxy에서 들어오지만 X-Forwarded-For는 다른 client IP.
        TestSession first = new TestSession("s1", "10.0.0.50", "203.0.113.1");
        TestSession second = new TestSession("s2", "10.0.0.50", "203.0.113.2");

        // remoteAddr = trusted, XFF leftmost = different IPs → 두 세션 모두 등록 가능 (max-per-ip=1)
        assertThat(registry.register(first)).isTrue();
        assertThat(registry.register(second)).isTrue();
    }

    @Test
    void registryRejectsSpoofedLeftmostForwardedForEntry() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(
                objectMapper,
                properties(10, 1, 10, 30, 30, 50),
                securityProps(List.of("10.0.0.0/8")),
                metrics()
        );
        // 신뢰 proxy가 실제 client IP를 우측에 append했다면 client가 주입한 좌측 값은 무시해야 한다.
        TestSession first = new TestSession("s1", "10.0.0.10", "203.0.113.5, 198.51.100.5");
        TestSession second = new TestSession("s2", "10.0.0.10", "203.0.113.99, 198.51.100.5");

        assertThat(registry.register(first)).isTrue();
        // 우측의 실제 client IP가 같으므로 max-per-ip=1에 의해 두 번째는 거절되어야 한다.
        assertThat(registry.register(second)).isFalse();
    }

    @Test
    void handlerRespondsInvalidActionForMalformedJson() throws Exception {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, props, securityProps(), metrics());
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(objectMapper, props, mock(GatewayTokenAuthenticator.class), registry);
        TestSession session = new TestSession("s1", "10.0.0.1");
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("not-json"));

        assertThat(session.open).isTrue();
        assertThat(session.sentText()).anySatisfy(payload -> assertThat(payload).contains("INVALID_ACTION"));
    }

    @Test
    void defaultAllowedOriginPatternsExcludeLocalhost() {
        GatewayWebSocketProperties defaults = new GatewayWebSocketProperties(
                30, 4096, "", null, 30, 10, 1_000, 2,
                10_000, 10, 5, 30, 30, 50, 64
        );

        assertThat(defaults.allowedOriginPatterns())
                .doesNotContain("http://localhost:*", "http://127.0.0.1:*")
                .containsExactly("https://*.apps.tossmini.com", "https://*.private-apps.tossmini.com");
    }

    @Test
    void privateSubscribersDeliversToAllSessionsOfSameUser() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(
                objectMapper, properties(10, 10, 5, 30, 30, 50), securityProps(), metrics());
        TestSession a = new TestSession("a", "10.0.0.1");
        TestSession b = new TestSession("b", "10.0.0.2");
        registry.register(a);
        registry.register(b);
        // 같은 userId(7L)로 두 세션을 인증 → private 구독은 channel + ":u:7" 키 하나에 모인다.
        assertThat(registry.authenticate(registry.get(a), 7L)).isTrue();
        assertThat(registry.authenticate(registry.get(b), 7L)).isTrue();
        registry.get(a).subscribe(List.of("order"), null, null);
        registry.get(b).subscribe(List.of("order"), null, null);

        List<GatewayWebSocketSessionRegistry.ClientSession> subscribers =
                registry.privateSubscribers(7L, "order");

        assertThat(subscribers).hasSize(2);
        assertThat(subscribers).extracting(GatewayWebSocketSessionRegistry.ClientSession::session)
                .containsExactlyInAnyOrder(registry.get(a).session(), registry.get(b).session());
    }

    @Test
    void privateSubscribersIsolatedByUserIdEvenWhenManyUsersSubscribeSameChannel() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(
                objectMapper, properties(50, 50, 10, 30, 30, 50), securityProps(), metrics());
        // userId 100..104 각각 하나씩 portfolio 구독
        TestSession[] sessions = new TestSession[5];
        for (int i = 0; i < 5; i++) {
            sessions[i] = new TestSession("s" + i, "10.0.0." + (i + 1));
            registry.register(sessions[i]);
            assertThat(registry.authenticate(registry.get(sessions[i]), 100L + i)).isTrue();
            registry.get(sessions[i]).subscribe(List.of("portfolio"), null, null);
        }

        // userId=102에게만 fanout 시 그 세션 하나만 반환되어야 함.
        List<GatewayWebSocketSessionRegistry.ClientSession> targeted =
                registry.privateSubscribers(102L, "portfolio");
        assertThat(targeted).hasSize(1);
        assertThat(targeted.get(0).session()).isEqualTo(registry.get(sessions[2]).session());

        // 인덱스 미존재 userId는 빈 결과.
        assertThat(registry.privateSubscribers(999L, "portfolio")).isEmpty();
        // null userId는 빈 결과(가드).
        assertThat(registry.privateSubscribers(null, "portfolio")).isEmpty();
    }

    @Test
    void privateSubscriberIndexEvictsKeyOnUnsubscribeAndUnregister() {
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(
                objectMapper, properties(10, 10, 5, 30, 30, 50), securityProps(), metrics());
        TestSession a = new TestSession("a", "10.0.0.1");
        TestSession b = new TestSession("b", "10.0.0.2");
        registry.register(a);
        registry.register(b);
        assertThat(registry.authenticate(registry.get(a), 42L)).isTrue();
        assertThat(registry.authenticate(registry.get(b), 42L)).isTrue();
        registry.get(a).subscribe(List.of("order"), null, null);
        registry.get(b).subscribe(List.of("order"), null, null);
        assertThat(registry.privateSubscribers(42L, "order")).hasSize(2);

        // unsubscribe로 한 세션 제거 → 다른 세션은 여전히 받음.
        registry.get(a).unsubscribe(List.of("order"), null, null);
        List<GatewayWebSocketSessionRegistry.ClientSession> afterUnsub = registry.privateSubscribers(42L, "order");
        assertThat(afterUnsub).hasSize(1);
        assertThat(afterUnsub.get(0).session()).isEqualTo(registry.get(b).session());

        // 마지막 세션도 unregister → 키 자체가 비고 빈 결과.
        registry.unregister(b);
        assertThat(registry.privateSubscribers(42L, "order")).isEmpty();
    }

    @Test
    void broadcasterIsolatesFailedSessionDuringFanout() {
        GatewayWebSocketProperties props = properties(10, 10, 5, 30, 30, 50);
        GatewayWebSocketSessionRegistry registry = new GatewayWebSocketSessionRegistry(objectMapper, props, securityProps(), metrics());
        GatewayWebSocketBroadcaster broadcaster = new GatewayWebSocketBroadcaster(registry, props, metrics());
        TestSession first = new TestSession("s1", "10.0.0.1");
        TestSession second = new TestSession("s2", "10.0.0.2");
        FailingSession failing = new FailingSession("s3", "10.0.0.3");
        registry.register(first);
        registry.register(second);
        registry.register(failing);
        registry.get(first).subscribe(List.of("ticker"), "KRW-BTC", null);
        registry.get(second).subscribe(List.of("ticker"), "KRW-BTC", null);
        registry.get(failing).subscribe(List.of("ticker"), "KRW-BTC", null);

        int delivered = broadcaster.broadcastPublic(new PublicGatewayEvent("ticker.update", "KRW-BTC", Instant.parse("2026-04-25T00:00:00Z"), Map.of("tradePrice", 1)));

        assertThat(delivered).isEqualTo(2);
        assertThat(first.sentText()).hasSize(1);
        assertThat(second.sentText()).hasSize(1);
    }

    private static GatewaySecurityHardeningProperties securityProps() {
        return new GatewaySecurityHardeningProperties();
    }

    private static int pendingAuthDeadlineCount(GatewayWebSocketHandler handler) throws Exception {
        java.lang.reflect.Field field = GatewayWebSocketHandler.class.getDeclaredField("authDeadlines");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(handler)).size();
    }

    private static int queuedAuthDeadlineCount(GatewayWebSocketHandler handler) throws Exception {
        java.lang.reflect.Field field = GatewayWebSocketHandler.class.getDeclaredField("authDeadlineExecutor");
        field.setAccessible(true);
        var executor = (java.util.concurrent.ScheduledThreadPoolExecutor) field.get(handler);
        return executor.getQueue().size();
    }

    private static GatewaySecurityHardeningProperties securityProps(List<String> trustedProxyCidrs) {
        GatewaySecurityHardeningProperties props = new GatewaySecurityHardeningProperties();
        props.setTrustedProxyCidrs(trustedProxyCidrs);
        return props;
    }

    private static GatewayWebSocketProperties properties(int maxGlobal, int maxIp, int maxUser, int maxMessages, int maxAttempts, int maxSubscriptions) {
        return properties(maxGlobal, maxIp, maxUser, maxMessages, maxAttempts, maxSubscriptions, 64);
    }

    private static GatewayWebSocketProperties properties(int maxGlobal, int maxIp, int maxUser, int maxMessages, int maxAttempts, int maxSubscriptions, int fanoutConcurrency) {
        return new GatewayWebSocketProperties(
                30,
                4096,
                "token",
                List.of("*"),
                30,
                10,
                1_000,
                2,
                maxGlobal,
                maxIp,
                maxUser,
                maxAttempts,
                maxMessages,
                maxSubscriptions,
                fanoutConcurrency
        );
    }

    private static class TestSession implements WebSocketSession {
        private final String id;
        private final String ip;
        private final URI uri;
        private final HttpHeaders headers = new HttpHeaders();
        private final List<String> sent = new ArrayList<>();
        private int textMessageSizeLimit;
        private boolean open = true;
        private CloseStatus closeStatus;

        private TestSession(String id, String ip) {
            this(id, ip, ip, URI.create("ws://localhost/ws"));
        }

        private TestSession(String id, String ip, String forwardedFor) {
            this(id, ip, forwardedFor, URI.create("ws://localhost/ws"));
        }

        private TestSession(String id, String ip, String forwardedFor, URI uri) {
            this.id = id;
            this.ip = ip;
            this.uri = uri;
            headers.set("X-Forwarded-For", forwardedFor);
        }

        private List<String> sentText() {
            return sent;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return uri;
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return headers;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return new HashMap<>();
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(ip, 12345);
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
            this.textMessageSizeLimit = messageSizeLimit;
        }

        @Override
        public int getTextMessageSizeLimit() {
            return textMessageSizeLimit;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 0;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            sent.add(message.getPayload().toString());
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            close(CloseStatus.NORMAL);
        }

        @Override
        public void close(CloseStatus status) {
            this.open = false;
            this.closeStatus = status;
        }
    }

    private static final class FailingSession extends TestSession {
        private FailingSession(String id, String ip) {
            super(id, ip);
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            throw new IOException("simulated send failure");
        }
    }

    private static final class SlowSession extends TestSession {
        private final java.time.Duration delay;

        private SlowSession(String id, String ip, java.time.Duration delay) {
            super(id, ip);
            this.delay = delay;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting", ex);
            }
            super.sendMessage(message);
        }
    }

    private static final class SendConcurrencyProbe {
        private final CountDownLatch configuredConcurrencyReached;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();

        private SendConcurrencyProbe(int configuredConcurrency) {
            this.configuredConcurrencyReached = new CountDownLatch(configuredConcurrency);
        }

        private void enter() throws InterruptedException {
            int activeNow = active.incrementAndGet();
            maxActive.accumulateAndGet(activeNow, Math::max);
            configuredConcurrencyReached.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
            } finally {
                active.decrementAndGet();
            }
        }

        private boolean awaitConfiguredConcurrency() throws InterruptedException {
            return configuredConcurrencyReached.await(1, TimeUnit.SECONDS);
        }

        private int maxActive() {
            return maxActive.get();
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class ConcurrencyTrackingSession extends TestSession {
        private final SendConcurrencyProbe probe;

        private ConcurrencyTrackingSession(String id, String ip, SendConcurrencyProbe probe) {
            super(id, ip);
            this.probe = probe;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            try {
                probe.enter();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while probing fanout concurrency", exception);
            }
            super.sendMessage(message);
        }
    }

    private static final class CapturingDeadlineExecutor extends java.util.concurrent.ScheduledThreadPoolExecutor {
        private final List<Runnable> tasks = new ArrayList<>();

        private CapturingDeadlineExecutor() {
            super(1);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(
                Runnable command,
                long delay,
                TimeUnit unit
        ) {
            tasks.add(command);
            return super.schedule(() -> {
            }, 1, TimeUnit.DAYS);
        }

        private int taskCount() {
            return tasks.size();
        }

        private void runTask(int index) {
            tasks.get(index).run();
        }
    }

    private static final class CountingObjectMapper extends ObjectMapper {
        private final java.util.concurrent.atomic.AtomicInteger serializeCount = new java.util.concurrent.atomic.AtomicInteger();

        int serializeCount() {
            return serializeCount.get();
        }

        void resetCount() {
            serializeCount.set(0);
        }

        @Override
        public String writeValueAsString(Object value) throws tools.jackson.core.JacksonException {
            serializeCount.incrementAndGet();
            return super.writeValueAsString(value);
        }
    }
}
