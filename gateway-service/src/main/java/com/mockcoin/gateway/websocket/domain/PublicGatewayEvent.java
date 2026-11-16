package com.mockcoin.gateway.websocket.domain;

import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

// gateway public WebSocket 이벤트의 서비스/전송 공통 입력 모델
@Schema(description = "앱 공개 채널로 보낼 이벤트")
public record PublicGatewayEvent(
        @Schema(description = "이벤트 종류. `ticker.update`=현재가(`ticker` 채널), `orderbook.snapshot`=호가(`orderbook` 채널), `candle.reconcile`=보정된 캔들(`candle` 채널), `marketList.update`=마켓 목록 변경(`marketList` 채널).",
                allowableValues = {"ticker.update", "orderbook.snapshot", "candle.reconcile", "marketList.update"}, example = "ticker.update",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String type,
        @Schema(description = "마켓 코드. 마켓 단위 채널이면 그 마켓 구독자에게만 보낸다. `marketList.update`에서는 쓰지 않는다.", example = "KRW-BTC")
        String market,
        @Schema(description = "이벤트 시각(ISO-8601 UTC). 앱에는 epoch 밀리초로 바꿔 보낸다. 없으면 지금.", example = "2026-01-01T00:00:00Z")
        Instant ts,
        @Schema(description = "앱에 그대로 전달할 데이터. `candle.reconcile`이면 `interval`로 구독 단위를 고른다.")
        Map<String, Object> data
) {
}
