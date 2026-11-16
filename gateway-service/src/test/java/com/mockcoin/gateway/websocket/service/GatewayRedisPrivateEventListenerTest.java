package com.mockcoin.gateway.websocket.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.websocket.config.GatewayRedisPrivateEventProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

class GatewayRedisPrivateEventListenerTest {
    @Test
    void broadcastsValidRedisPrivateEventPayload() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPrivateEventListener listener = new GatewayRedisPrivateEventListener(new ObjectMapper(), broadcaster, new GatewayRedisPrivateEventProperties(true, "ws:private:"));
        String payload = """
                {"userId":777,"type":"order.update","ts":"2026-04-25T00:00:00Z","data":{"orderId":123}}
                """;

        listener.onMessage(new DefaultMessage("ws:private:777".getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8)), null);

        verify(broadcaster).broadcastPrivate(org.mockito.ArgumentMatchers.eq(777L), org.mockito.ArgumentMatchers.argThat(event ->
                "order.update".equals(event.type())
                        && event.data().containsKey("orderId")
        ));
    }

    @Test
    void ignoresPayloadWhenChannelUserDoesNotMatchPayloadUser() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPrivateEventListener listener = new GatewayRedisPrivateEventListener(new ObjectMapper(), broadcaster, new GatewayRedisPrivateEventProperties(true, "ws:private:"));
        String payload = """
                {"userId":777,"type":"order.update","ts":"2026-04-25T00:00:00Z","data":{"orderId":123}}
                """;

        listener.onMessage(new DefaultMessage("ws:private:778".getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8)), null);

        verify(broadcaster, never()).broadcastPrivate(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ignoresUnsupportedPrivateEventTypeFromRedis() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPrivateEventListener listener = new GatewayRedisPrivateEventListener(new ObjectMapper(), broadcaster, new GatewayRedisPrivateEventProperties(true, "ws:private:"));
        String payload = """
                {"userId":777,"type":"ticker.update","ts":"2026-04-25T00:00:00Z","data":{"tradePrice":100}}
                """;

        listener.onMessage(new DefaultMessage("ws:private:777".getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8)), null);

        verify(broadcaster, never()).broadcastPrivate(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }
}
