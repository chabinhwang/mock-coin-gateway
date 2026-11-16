package com.mockcoin.gateway.websocket.service;

import java.nio.charset.StandardCharsets;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.websocket.domain.PublicGatewayEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

// Redis md:broadcast:* 채널에서 public 이벤트를 수신해 local 세션으로 fanout한다
@Component
public class GatewayRedisMarketEventListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(GatewayRedisMarketEventListener.class);

    private final ObjectMapper objectMapper;
    private final GatewayWebSocketBroadcaster broadcaster;

    // ObjectMapper와 브로드캐스터를 주입받는다.
    public GatewayRedisMarketEventListener(ObjectMapper objectMapper, GatewayWebSocketBroadcaster broadcaster) {
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
    }

    // Redis 메시지를 PublicGatewayEvent로 역직렬화해 broadcaster로 전달한다
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            PublicGatewayEvent event = objectMapper.readValue(payload, PublicGatewayEvent.class);
            broadcaster.broadcastPublic(event);
        } catch (RuntimeException exception) {
            log.warn("Redis market event ignored reason={} payload={}", exception.getMessage(), payload);
        } catch (Exception exception) {
            log.warn("Redis market event ignored reason={} payload={}", exception.getMessage(), payload);
        }
    }
}
