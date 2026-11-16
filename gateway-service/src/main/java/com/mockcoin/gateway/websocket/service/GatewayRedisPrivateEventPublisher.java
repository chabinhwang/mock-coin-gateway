package com.mockcoin.gateway.websocket.service;

import java.time.Instant;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.websocket.config.GatewayRedisPrivateEventProperties;
import com.mockcoin.gateway.websocket.domain.GatewayRedisPrivateEvent;
import com.mockcoin.gateway.websocket.domain.PrivateGatewayEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// internal private 이벤트를 Redis ws:private:{userId} 채널로 publish한다
@Component
public class GatewayRedisPrivateEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(GatewayRedisPrivateEventPublisher.class);

    private final GatewayRedisPrivateEventProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // private Redis 이벤트 설정, Redis 템플릿, ObjectMapper를 주입받는다.
    public GatewayRedisPrivateEventPublisher(GatewayRedisPrivateEventProperties properties, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // private 이벤트를 사용자별 Redis 채널로 publish하며 구독자가 없거나 비활성이면 skipped 반환
    public PublishResult publish(Long userId, PrivateGatewayEvent event) {
        if (!properties.enabled()) {
            return PublishResult.skipped("redis_private_events_disabled");
        }
        if (userId == null) {
            return PublishResult.skipped("user_id_missing");
        }
        try {
            GatewayRedisPrivateEvent payload = new GatewayRedisPrivateEvent(userId, event.type(), event.ts() == null ? Instant.now() : event.ts(), event.data());
            Long subscribers = redisTemplate.convertAndSend(properties.channelPrefix() + userId, objectMapper.writeValueAsString(payload));
            if (subscribers == null || subscribers <= 0) {
                return PublishResult.skipped("gateway_redis_private_event_no_subscribers");
            }
            return PublishResult.success();
        } catch (JacksonException exception) {
            log.warn("Gateway Redis private event serialization failed userId={} type={} reason={}", userId, event.type(), exception.getMessage());
            return PublishResult.skipped("gateway_redis_private_event_serialization_failed");
        } catch (RuntimeException exception) {
            log.warn("Gateway Redis private event publish failed userId={} type={} reason={}", userId, event.type(), exception.getMessage());
            return PublishResult.skipped("gateway_redis_private_event_publish_failed");
        }
    }

    // Redis publish 결과(게시 여부 + 성공·건너뜀 사유)
    public record PublishResult(boolean published, String reason) {
        static PublishResult success() {
            return new PublishResult(true, "published_to_gateway_private_redis");
        }

        static PublishResult skipped(String reason) {
            return new PublishResult(false, reason);
        }
    }
}
