package com.mockcoin.gateway.websocket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.websocket.service.GatewayRedisPublicEventPublisher;
import com.mockcoin.gateway.websocket.service.GatewayWebSocketBroadcaster;
import com.mockcoin.gateway.websocket.domain.PublicGatewayEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class InternalGatewayWebSocketEventControllerTest {
    @Test
    void broadcastsPublicEventTextFrameAndSendsAckResponse() throws Exception {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPublicEventPublisher publisher = mock(GatewayRedisPublicEventPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper();
        InternalGatewayWebSocketEventController controller = new InternalGatewayWebSocketEventController(objectMapper, broadcaster, publisher);
        WebSocketSession session = mock(WebSocketSession.class);
        when(publisher.publish(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new GatewayRedisPublicEventPublisher.PublishResult(false, "redis_market_events_disabled"));
        when(broadcaster.broadcastPublic(org.mockito.ArgumentMatchers.any())).thenReturn(3);

        controller.handleEventText(session, """
                {"type":"candle.reconcile","market":"KRW-BTC","ts":"2026-04-25T00:00:00Z","data":{"interval":"1m"}}
                """);

        verify(broadcaster).broadcastPublic(argThat((PublicGatewayEvent event) ->
                "candle.reconcile".equals(event.type())
                        && "KRW-BTC".equals(event.market())
                        && Instant.parse("2026-04-25T00:00:00Z").equals(event.ts())
        ));
        verify(session).sendMessage(argThat((TextMessage message) -> {
            assertThat(message.getPayload()).contains("\"success\":true");
            assertThat(message.getPayload()).contains("\"delivered\":3");
            return true;
        }));
    }

    @Test
    void rejectsUnsupportedPublicEventTextFrameBeforeRedisPublish() throws Exception {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPublicEventPublisher publisher = mock(GatewayRedisPublicEventPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper();
        InternalGatewayWebSocketEventController controller = new InternalGatewayWebSocketEventController(objectMapper, broadcaster, publisher);
        WebSocketSession session = mock(WebSocketSession.class);

        controller.handleEventText(session, """
                {"type":"trade.update","market":"KRW-BTC","ts":"2026-04-25T00:00:00Z","data":{"price":100}}
                """);

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
        verify(broadcaster, never()).broadcastPublic(org.mockito.ArgumentMatchers.any());
        verify(session).sendMessage(argThat((TextMessage message) -> {
            assertThat(message.getPayload()).contains("\"success\":false");
            assertThat(message.getPayload()).contains("지원하지 않는 WebSocket public event type");
            return true;
        }));
    }
}
