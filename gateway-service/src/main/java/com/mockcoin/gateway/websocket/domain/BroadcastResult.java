package com.mockcoin.gateway.websocket.domain;

import io.swagger.v3.oas.annotations.media.Schema;

// internal WebSocket event API의 fanout 결과 모델(-1은 비동기 Redis accepted)
@Schema(description = "이벤트 전달 결과")
public record BroadcastResult(
        @Schema(description = "이 인스턴스가 직접 보낸 WebSocket 세션 수. Redis로 다른 인스턴스에 넘겼으면 0이고, `marketList.update`를 Redis로 넘겼으면 -1(비동기 전달 접수)이다.",
                example = "3")
        int delivered
) {
}
