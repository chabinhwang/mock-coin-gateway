package com.mockcoin.gateway.websocket.config;

import com.mockcoin.gateway.websocket.controller.InternalGatewayWebSocketEventController;
import com.mockcoin.gateway.websocket.service.GatewayWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

// /ws 엔드포인트에 gateway WebSocket 핸들러를 등록한다
@Configuration
@EnableWebSocket
public class GatewayWebSocketConfiguration implements WebSocketConfigurer {
    private final GatewayWebSocketHandler handler;
    private final InternalGatewayWebSocketEventController internalGatewayEventController;
    private final GatewayWebSocketProperties properties;

    // 앱 WebSocket 핸들러, 내부 이벤트 WebSocket 핸들러, WebSocket 설정을 주입받는다.
    public GatewayWebSocketConfiguration(
            GatewayWebSocketHandler handler,
            InternalGatewayWebSocketEventController internalGatewayEventController,
            GatewayWebSocketProperties properties
    ) {
        this.handler = handler;
        this.internalGatewayEventController = internalGatewayEventController;
        this.properties = properties;
    }

    // 허용 origin 패턴과 함께 /ws 경로에 핸들러를 등록한다
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .setAllowedOriginPatterns(properties.allowedOriginPatterns().toArray(String[]::new));
        registry.addHandler(internalGatewayEventController, "/internal/gateway/events/public/ws")
                .setAllowedOrigins("*");
    }
}
