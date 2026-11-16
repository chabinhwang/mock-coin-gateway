package com.mockcoin.gateway.websocket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.websocket.config.GatewayRedisPrivateEventProperties;
import com.mockcoin.gateway.websocket.domain.PrivateGatewayEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class GatewayRedisPrivateEventPublisherTest {
    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void skipsWhenDisabledWithoutTouchingRedis() {
        GatewayRedisPrivateEventPublisher publisher = new GatewayRedisPrivateEventPublisher(
                new GatewayRedisPrivateEventProperties(false, "ws:private:"),
                redisTemplate,
                new ObjectMapper()
        );

        GatewayRedisPrivateEventPublisher.PublishResult result = publisher.publish(777L, event());

        assertThat(result.published()).isFalse();
        assertThat(result.reason()).isEqualTo("redis_private_events_disabled");
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void publishesPrivateEventToUserChannel() {
        GatewayRedisPrivateEventPublisher publisher = new GatewayRedisPrivateEventPublisher(
                new GatewayRedisPrivateEventProperties(true, "ws:private:"),
                redisTemplate,
                new ObjectMapper()
        );
        when(redisTemplate.convertAndSend(org.mockito.ArgumentMatchers.eq("ws:private:777"), org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);

        GatewayRedisPrivateEventPublisher.PublishResult result = publisher.publish(777L, event());

        assertThat(result.published()).isTrue();
        verify(redisTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq("ws:private:777"), org.mockito.ArgumentMatchers.contains("order.update"));
    }

    @Test
    void fallsBackWhenRedisReportsNoSubscribers() {
        GatewayRedisPrivateEventPublisher publisher = new GatewayRedisPrivateEventPublisher(
                new GatewayRedisPrivateEventProperties(true, "ws:private:"),
                redisTemplate,
                new ObjectMapper()
        );
        when(redisTemplate.convertAndSend(org.mockito.ArgumentMatchers.eq("ws:private:777"), org.mockito.ArgumentMatchers.anyString())).thenReturn(0L);

        GatewayRedisPrivateEventPublisher.PublishResult result = publisher.publish(777L, event());

        assertThat(result.published()).isFalse();
        assertThat(result.reason()).isEqualTo("gateway_redis_private_event_no_subscribers");
    }

    private static PrivateGatewayEvent event() {
        return new PrivateGatewayEvent("order.update", Instant.parse("2026-04-25T00:00:00Z"), Map.of("orderId", 123));
    }
}
