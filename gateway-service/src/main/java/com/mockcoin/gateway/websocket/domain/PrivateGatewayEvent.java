package com.mockcoin.gateway.websocket.domain;

import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

// gateway private WebSocket 이벤트의 서비스/전송 공통 입력 모델
@Schema(description = "한 사용자에게 보낼 이벤트")
public record PrivateGatewayEvent(
        @Schema(description = "이벤트 종류. `order.update`=주문 상태 변경(`order` 채널), `portfolio.update`=잔고·보유 변경(`portfolio` 채널).",
                allowableValues = {"order.update", "portfolio.update"}, example = "order.update", requiredMode = Schema.RequiredMode.REQUIRED)
        String type,
        @Schema(description = "이벤트 시각(ISO-8601 UTC). 앱에는 epoch 밀리초로 바꿔 보낸다. 없으면 지금.", example = "2026-01-01T00:00:00Z")
        Instant ts,
        @Schema(description = "앱에 그대로 전달할 데이터")
        Map<String, Object> data
) {
}
