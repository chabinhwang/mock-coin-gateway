package com.mockcoin.gateway.websocket.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.websocket.config.GatewayRedisMarketEventProperties;
import com.mockcoin.gateway.websocket.domain.PublicGatewayEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// public 이벤트를 market별 또는 marketList 전역 예약 Redis 채널로 publish한다
@Component
public class GatewayRedisPublicEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(GatewayRedisPublicEventPublisher.class);
    private static final String MARKET_LIST_CHANNEL_SUFFIX = "__marketList__";

    private final GatewayRedisMarketEventProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // market Redis 이벤트 설정, Redis 템플릿, ObjectMapper를 주입받는다.
    public GatewayRedisPublicEventPublisher(GatewayRedisMarketEventProperties properties, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // market별/전역 Redis 채널로 publish하며 구독자가 없거나 비활성이면 skipped 반환
    public PublishResult publish(PublicGatewayEvent event) {
        if (!properties.enabled()) {
            return PublishResult.skipped("redis_market_events_disabled");
        }
        String channelSuffix = channelSuffix(event);
        if (channelSuffix == null) {
            return PublishResult.skipped("market_missing");
        }
        try {
            Long subscribers = redisTemplate.convertAndSend(
                    properties.channelPrefix() + channelSuffix,
                    objectMapper.writeValueAsString(event)
            );
            if (subscribers == null || subscribers <= 0) {
                return PublishResult.skipped("gateway_redis_market_event_no_subscribers");
            }
            return PublishResult.success();
        } catch (JacksonException exception) {
            log.warn("Gateway Redis public event serialization failed market={} type={} reason={}", event.market(), event.type(), exception.getMessage());
            return PublishResult.skipped("gateway_redis_market_event_serialization_failed");
        } catch (RuntimeException exception) {
            log.warn("Gateway Redis public event publish failed market={} type={} reason={}", event.market(), event.type(), exception.getMessage());
            return PublishResult.skipped("gateway_redis_market_event_publish_failed");
        }
    }

    // marketList.update는 전역 예약 채널을, 그 외는 market을 채널 접미사로 쓰고, market이 없으면 null을 반환한다.
    private static String channelSuffix(PublicGatewayEvent event) {
        if (event == null) {
            return null;
        }
        if ("marketList.update".equals(event.type())) {
            return MARKET_LIST_CHANNEL_SUFFIX;
        }
        return event.market() == null || event.market().isBlank() ? null : event.market();
    }

    // Redis publish 결과(게시 여부 + 성공·건너뜀 사유)
    public record PublishResult(boolean published, String reason) {
        static PublishResult success() {
            return new PublishResult(true, "published_to_gateway_market_redis");
        }

        static PublishResult skipped(String reason) {
            return new PublishResult(false, reason);
        }
    }
}
