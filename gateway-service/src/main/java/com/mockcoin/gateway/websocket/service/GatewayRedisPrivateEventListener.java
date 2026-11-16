package com.mockcoin.gateway.websocket.service;

import java.nio.charset.StandardCharsets;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.websocket.config.GatewayRedisPrivateEventProperties;
import com.mockcoin.gateway.websocket.domain.GatewayRedisPrivateEvent;
import com.mockcoin.gateway.websocket.domain.PrivateGatewayEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

// Redis ws:private:* 채널에서 private 이벤트를 수신해 해당 사용자 세션으로 fanout한다
@Component
public class GatewayRedisPrivateEventListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(GatewayRedisPrivateEventListener.class);

    private final ObjectMapper objectMapper;
    private final GatewayWebSocketBroadcaster broadcaster;
    private final GatewayRedisPrivateEventProperties properties;

    // ObjectMapper, 브로드캐스터, private Redis 이벤트 설정을 주입받는다.
    public GatewayRedisPrivateEventListener(ObjectMapper objectMapper, GatewayWebSocketBroadcaster broadcaster, GatewayRedisPrivateEventProperties properties) {
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.properties = properties;
    }

    // 채널 userId와 payload userId 일치를 검증한 뒤 private 이벤트를 broadcast 한다
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            GatewayRedisPrivateEvent event = objectMapper.readValue(payload, GatewayRedisPrivateEvent.class);
            Long channelUserId = userIdFromChannel(new String(message.getChannel(), StandardCharsets.UTF_8));
            if (!event.userId().equals(channelUserId)) {
                log.warn("Redis private event ignored reason=user_id_channel_mismatch channelUserId={} payloadUserId={} type={}", channelUserId, event.userId(), event.type());
                return;
            }
            if (!isSupportedPrivateType(event.type())) {
                log.warn("Redis private event ignored reason=unsupported_type userId={} type={}", event.userId(), event.type());
                return;
            }
            broadcaster.broadcastPrivate(event.userId(), new PrivateGatewayEvent(event.type(), event.ts(), event.data()));
        } catch (RuntimeException exception) {
            log.warn("Redis private event ignored reason=invalid_payload");
        } catch (Exception exception) {
            log.warn("Redis private event ignored reason=invalid_payload");
        }
    }

    // private 채널로 허용하는 이벤트 타입(order.update, portfolio.update)인지 확인한다.
    private boolean isSupportedPrivateType(String type) {
        return "order.update".equals(type) || "portfolio.update".equals(type);
    }

    // 채널 이름에서 prefix 뒤의 userId를 꺼내고, prefix가 다르면 예외를 던진다.
    private Long userIdFromChannel(String channel) {
        if (channel == null || !channel.startsWith(properties.channelPrefix())) {
            throw new IllegalArgumentException("private event channel prefix mismatch");
        }
        String userId = channel.substring(properties.channelPrefix().length());
        return Long.valueOf(userId);
    }
}
