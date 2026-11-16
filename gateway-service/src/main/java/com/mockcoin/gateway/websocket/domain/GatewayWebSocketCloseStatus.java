package com.mockcoin.gateway.websocket.domain;

import org.springframework.web.socket.CloseStatus;

// gateway WebSocket 강제 종료 시 사용하는 도메인 close status 코드 모음
public final class GatewayWebSocketCloseStatus {
    public static final CloseStatus AUTH_REQUIRED = new CloseStatus(4001, "AUTH_REQUIRED");
    public static final CloseStatus FORCE_CLOSED = new CloseStatus(4002, "FORCE_CLOSED");
    public static final CloseStatus CONNECTION_LIMIT = new CloseStatus(4003, "CONNECTION_LIMIT");
    public static final CloseStatus RATE_LIMITED = new CloseStatus(4004, "RATE_LIMITED");
    public static final CloseStatus AUTH_SERVICE_UNAVAILABLE = new CloseStatus(1013, "AUTH_SERVICE_UNAVAILABLE");

    private GatewayWebSocketCloseStatus() {
    }
}
