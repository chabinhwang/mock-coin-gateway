package com.mockcoin.gateway.websocket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.global.security.GatewayTokenAuthenticator;
import com.mockcoin.gateway.websocket.config.GatewayWebSocketProperties;
import org.junit.jupiter.api.Test;

class GatewayWebSocketHandlerLifecycleTest {

    @Test
    void shutdownAuthDeadlineExecutorTerminatesScheduler() throws Exception {
        GatewayWebSocketProperties properties = new GatewayWebSocketProperties(
                30, 4096, "", java.util.List.of(),
                30, 10, 1_000L, 2,
                10_000, 10, 5, 30, 30, 50, 64);
        GatewayWebSocketHandler handler = new GatewayWebSocketHandler(
                new ObjectMapper(),
                properties,
                mock(GatewayTokenAuthenticator.class),
                mock(GatewayWebSocketSessionRegistry.class));

        Field executorField = GatewayWebSocketHandler.class.getDeclaredField("authDeadlineExecutor");
        executorField.setAccessible(true);
        ScheduledExecutorService executor = (ScheduledExecutorService) executorField.get(handler);

        assertThat(executor.isShutdown()).isFalse();
        handler.shutdownAuthDeadlineExecutor();
        assertThat(executor.isShutdown()).isTrue();
        assertThat(executor.isTerminated()).isTrue();
    }
}
