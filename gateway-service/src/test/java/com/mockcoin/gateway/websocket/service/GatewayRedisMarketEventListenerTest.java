package com.mockcoin.gateway.websocket.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

class GatewayRedisMarketEventListenerTest {
    @Test
    void broadcastsValidRedisMarketEventPayload() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisMarketEventListener listener = new GatewayRedisMarketEventListener(new ObjectMapper(), broadcaster);
        String payload = """
                {"type":"ticker.update","market":"KRW-BTC","ts":"2026-04-25T00:00:00Z","data":{"tradePrice":100}}
                """;

        listener.onMessage(new DefaultMessage("md:broadcast:KRW-BTC".getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8)), null);

        verify(broadcaster).broadcastPublic(org.mockito.ArgumentMatchers.argThat(event ->
                "ticker.update".equals(event.type())
                        && "KRW-BTC".equals(event.market())
                        && event.data().containsKey("tradePrice")
        ));
    }

    @Test
    void broadcastsMarketListPayloadWithoutMarketScope() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisMarketEventListener listener = new GatewayRedisMarketEventListener(new ObjectMapper(), broadcaster);
        String payload = """
                {"type":"marketList.update","ts":"2026-04-25T00:00:00Z","data":{"markets":[{"market":"KRW-BTC","currentPrice":100}]}}
                """;

        listener.onMessage(new DefaultMessage(
                "md:broadcast:__marketList__".getBytes(StandardCharsets.UTF_8),
                payload.getBytes(StandardCharsets.UTF_8)), null);

        verify(broadcaster).broadcastPublic(org.mockito.ArgumentMatchers.argThat(event ->
                "marketList.update".equals(event.type())
                        && event.market() == null
                        && event.data().containsKey("markets")
        ));
    }
}
