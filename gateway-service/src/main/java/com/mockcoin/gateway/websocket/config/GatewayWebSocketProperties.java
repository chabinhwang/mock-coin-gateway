package com.mockcoin.gateway.websocket.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

// WebSocket hardening 설정(인증 타임아웃, 메시지 크기, ping/pong, 연결/구독 한도, fanout 동시성)
@ConfigurationProperties(prefix = "mockcoin.websocket")
public record GatewayWebSocketProperties(
        int authTimeoutSeconds,
        int maxMessageBytes,
        String internalEventToken,
        List<String> allowedOriginPatterns,
        int pingIntervalSeconds,
        int pongTimeoutSeconds,
        long heartbeatCheckIntervalMillis,
        int maxMissedPongs,
        int maxGlobalConnections,
        int maxConnectionsPerIp,
        int maxConnectionsPerUser,
        int maxConnectionAttemptsPerMinute,
        int maxMessagesPerSecond,
        int maxSubscriptionsPerSession,
        int fanoutConcurrency
) {
    // 0 이하·누락 값을 기본값으로 채우고, fanout 동시성은 최대 512로 제한한다.
    public GatewayWebSocketProperties {
        authTimeoutSeconds = authTimeoutSeconds <= 0 ? 30 : authTimeoutSeconds;
        maxMessageBytes = maxMessageBytes <= 0 ? 4096 : maxMessageBytes;
        internalEventToken = internalEventToken == null ? "" : internalEventToken;
        allowedOriginPatterns = allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()
                ? List.of("https://*.apps.tossmini.com", "https://*.private-apps.tossmini.com")
                : List.copyOf(allowedOriginPatterns);
        pingIntervalSeconds = pingIntervalSeconds <= 0 ? 30 : pingIntervalSeconds;
        pongTimeoutSeconds = pongTimeoutSeconds <= 0 ? 10 : pongTimeoutSeconds;
        heartbeatCheckIntervalMillis = heartbeatCheckIntervalMillis <= 0 ? 1_000 : heartbeatCheckIntervalMillis;
        maxMissedPongs = maxMissedPongs <= 0 ? 2 : maxMissedPongs;
        maxGlobalConnections = maxGlobalConnections <= 0 ? 10_000 : maxGlobalConnections;
        maxConnectionsPerIp = maxConnectionsPerIp <= 0 ? 10 : maxConnectionsPerIp;
        maxConnectionsPerUser = maxConnectionsPerUser <= 0 ? 5 : maxConnectionsPerUser;
        maxConnectionAttemptsPerMinute = maxConnectionAttemptsPerMinute <= 0 ? 30 : maxConnectionAttemptsPerMinute;
        maxMessagesPerSecond = maxMessagesPerSecond <= 0 ? 30 : maxMessagesPerSecond;
        maxSubscriptionsPerSession = maxSubscriptionsPerSession <= 0 ? 50 : maxSubscriptionsPerSession;
        fanoutConcurrency = fanoutConcurrency <= 0 ? 64 : Math.min(fanoutConcurrency, 512);
    }
}
