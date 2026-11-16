package com.mockcoin.gateway.websocket.controller;

import java.io.IOException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.websocket.service.GatewayRedisPublicEventPublisher;
import com.mockcoin.gateway.websocket.service.GatewayWebSocketBroadcaster;
import com.mockcoin.gateway.websocket.domain.PublicGatewayEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

// 서비스-투-서비스 public 이벤트를 WebSocket으로 수신해 게이트웨이 브로드캐스트로 전달한다
@Component
public class InternalGatewayWebSocketEventController extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(InternalGatewayWebSocketEventController.class);

    private final ObjectMapper objectMapper;
    private final GatewayWebSocketBroadcaster broadcaster;
    private final GatewayRedisPublicEventPublisher redisPublicEventPublisher;

    // ObjectMapper, 브로드캐스터, public Redis 이벤트 publisher를 주입받는다.
    public InternalGatewayWebSocketEventController(
            ObjectMapper objectMapper,
            GatewayWebSocketBroadcaster broadcaster,
            GatewayRedisPublicEventPublisher redisPublicEventPublisher
    ) {
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.redisPublicEventPublisher = redisPublicEventPublisher;
    }

    // 수신한 텍스트 메시지를 handleEventText로 넘긴다.
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        handleEventText(session, message.getPayload());
    }

    // 이벤트를 파싱·타입 검증한 뒤 Redis publish를 시도하고, 안 되면 로컬 세션에 직접 보낸 뒤 결과를 ack로 응답한다.
    public void handleEventText(WebSocketSession session, String payload) throws IOException {
        PublicGatewayEvent event = parseEvent(payload);
        if (event == null) {
            sendAck(session, new Ack(false, "gateway_public_event_invalid_payload", 0));
            return;
        }
        try {
            GatewayWebSocketBroadcaster.verifyPublicEventType(event);
            int delivered = 0;
            if (!redisPublicEventPublisher.publish(event).published()) {
                delivered = broadcaster.broadcastPublic(event);
            }
            log.debug("internal websocket public event delivered={}", delivered);
            sendAck(session, new Ack(true, null, delivered));
        } catch (Exception exception) {
            log.warn("Failed to broadcast internal websocket public event: {}", exception.getMessage());
            sendAck(session, new Ack(false, exception.getMessage(), 0));
        }
    }

    // payload를 PublicGatewayEvent로 역직렬화하고, 실패하면 null을 반환한다.
    private PublicGatewayEvent parseEvent(String payload) {
        try {
            return objectMapper.readValue(payload, PublicGatewayEvent.class);
        } catch (JacksonException exception) {
            log.warn("Invalid internal websocket public event payload: {}", exception.getMessage());
            return null;
        }
    }

    // ack를 JSON으로 직렬화해 같은 세션에 보낸다.
    private void sendAck(WebSocketSession session, Ack ack) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));
    }

    // 내부 이벤트 처리 ack(성공 여부, 실패 사유, 로컬 직접 전달 세션 수)
    public record Ack(boolean success, String error, int delivered) {
    }
}
