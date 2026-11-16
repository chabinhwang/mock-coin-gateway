package com.mockcoin.gateway.websocket.controller;

import com.mockcoin.gateway.websocket.service.GatewayRedisPrivateEventPublisher;
import com.mockcoin.gateway.websocket.service.GatewayRedisPublicEventPublisher;
import com.mockcoin.gateway.websocket.service.GatewayWebSocketBroadcaster;
import com.mockcoin.gateway.websocket.domain.BroadcastResult;
import com.mockcoin.gateway.websocket.domain.PrivateGatewayEvent;
import com.mockcoin.gateway.websocket.domain.PublicGatewayEvent;
import com.mockcoin.shared.api.ApiResponse;
import com.mockcoin.shared.api.CommonApiErrors;
import com.mockcoin.shared.api.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// downstream 서비스가 호출하는 public/private WebSocket 이벤트 internal endpoint
@RestController
@RequestMapping("/internal/gateway/events")
@Tag(name = "내부: 실시간 이벤트 전달", description = """
        다른 서비스가 앱 WebSocket(`/ws`) 구독자에게 보낼 이벤트를 게이트웨이에 넘긴다. market-service는 마켓 목록 갱신을,
        trading-service는 사용자별 주문·포트폴리오 변경을 보낸다(시세·호가는 market-service가 내부 WebSocket `/internal/gateway/events/public/ws`로 보낸다).
        Redis Pub/Sub 전달이 켜져 있고 구독 중인 게이트웨이가 있으면 Redis로 모든 인스턴스에 퍼뜨리고, 아니면 이 인스턴스의 구독자에게 직접 보낸다.""")
public class GatewayWebSocketEventController {
    private static final int ASYNC_DELIVERY_PENDING = -1;

    private final GatewayWebSocketBroadcaster broadcaster;
    private final GatewayRedisPublicEventPublisher redisPublicEventPublisher;
    private final GatewayRedisPrivateEventPublisher redisPrivateEventPublisher;

    // 브로드캐스터와 public·private Redis 이벤트 publisher를 주입받는다.
    public GatewayWebSocketEventController(GatewayWebSocketBroadcaster broadcaster, GatewayRedisPublicEventPublisher redisPublicEventPublisher, GatewayRedisPrivateEventPublisher redisPrivateEventPublisher) {
        this.broadcaster = broadcaster;
        this.redisPublicEventPublisher = redisPublicEventPublisher;
        this.redisPrivateEventPublisher = redisPrivateEventPublisher;
    }

    // public market 이벤트를 Redis Pub/Sub 또는 local fanout으로 전달한다
    @PostMapping("/public")
    @Operation(summary = "공개 이벤트 전달", description = """
            시세·호가·캔들 보정·마켓 목록 이벤트를 그 채널 구독자에게 보낸다. 보내는 메시지는 `{type, market, ts, data}`이며 `marketList.update`는 `market`을 빼고 보낸다.
            `ts`가 없으면 지금 시각을 쓴다. 같은 이벤트를 다시 보내면 구독자는 같은 메시지를 다시 받는다.""")
    @CommonApiErrors(CommonErrorCode.INVALID_PARAMETER)
    public ResponseEntity<ApiResponse<BroadcastResult>> publicEvent(@RequestBody PublicGatewayEvent event) {
        GatewayWebSocketBroadcaster.verifyPublicEventType(event);
        if (redisPublicEventPublisher.publish(event).published()) {
            int delivered = "marketList.update".equals(event.type()) ? ASYNC_DELIVERY_PENDING : 0;
            return ResponseEntity.ok(ApiResponse.success(new BroadcastResult(delivered)));
        }
        return ResponseEntity.ok(ApiResponse.success(new BroadcastResult(broadcaster.broadcastPublic(event))));
    }

    // 특정 사용자에 대한 private 이벤트(order/portfolio update)를 Redis 또는 local fanout으로 전달한다
    @PostMapping("/private/{userId}")
    @Operation(summary = "사용자 이벤트 전달", description = """
            한 사용자의 `order`·`portfolio` 채널 구독자(로그인한 세션)에게 이벤트를 보낸다. 보내는 메시지는 `{type, ts, data}`다. 같은 이벤트를 다시 보내면 다시 받는다.""")
    @CommonApiErrors(CommonErrorCode.INVALID_PARAMETER)
    public ResponseEntity<ApiResponse<BroadcastResult>> privateEvent(
            @Parameter(description = "이벤트를 받을 사용자 ID", example = "42") @PathVariable Long userId,
            @RequestBody PrivateGatewayEvent event) {
        verifyPrivateEventType(event);
        if (redisPrivateEventPublisher.publish(userId, event).published()) {
            return ResponseEntity.ok(ApiResponse.success(new BroadcastResult(0)));
        }
        return ResponseEntity.ok(ApiResponse.success(new BroadcastResult(broadcaster.broadcastPrivate(userId, event))));
    }

    // private 이벤트 타입이 order.update·portfolio.update가 아니면 INVALID_PARAMETER 예외를 던진다.
    private void verifyPrivateEventType(PrivateGatewayEvent event) {
        if (event == null || !("order.update".equals(event.type()) || "portfolio.update".equals(event.type()))) {
            throw CommonErrorCode.INVALID_PARAMETER.exception("지원하지 않는 WebSocket private event type입니다.");
        }
    }
}
