package com.mockcoin.gateway.websocket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.websocket.config.GatewayRedisMarketEventProperties;
import com.mockcoin.gateway.websocket.domain.PublicGatewayEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class GatewayRedisPublicEventPublisherTest {
    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void publishesMarketListUpdateWithoutInventingMarketScope() {
        GatewayRedisPublicEventPublisher publisher = publisher(true);
        PublicGatewayEvent event = new PublicGatewayEvent(
                "marketList.update",
                null,
                Instant.parse("2026-04-25T00:00:00Z"),
                Map.of("markets", List.of(Map.of("market", "KRW-BTC", "currentPrice", 100)))
        );
        when(redisTemplate.convertAndSend(eq("md:broadcast:__marketList__"), anyString())).thenReturn(2L);

        GatewayRedisPublicEventPublisher.PublishResult result = publisher.publish(event);

        assertThat(result.published()).isTrue();
        verify(redisTemplate).convertAndSend(
                eq("md:broadcast:__marketList__"),
                contains("marketList.update")
        );
    }

    @Test
    void stillRejectsMissingMarketForMarketScopedEvent() {
        GatewayRedisPublicEventPublisher publisher = publisher(true);
        PublicGatewayEvent event = new PublicGatewayEvent(
                "ticker.update",
                null,
                Instant.parse("2026-04-25T00:00:00Z"),
                Map.of("tradePrice", 100)
        );

        GatewayRedisPublicEventPublisher.PublishResult result = publisher.publish(event);

        assertThat(result.published()).isFalse();
        assertThat(result.reason()).isEqualTo("market_missing");
        verifyNoInteractions(redisTemplate);
    }

    private GatewayRedisPublicEventPublisher publisher(boolean enabled) {
        return new GatewayRedisPublicEventPublisher(
                new GatewayRedisMarketEventProperties(enabled, "md:broadcast:"),
                redisTemplate,
                new ObjectMapper()
        );
    }
}
