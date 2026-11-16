package com.mockcoin.gateway.websocket.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// 전역/IP/사용자 연결 수 + IP별 연결 시도율 한도를 관리하는 admission control 협력자
// GatewayWebSocketSessionRegistry에서 분리해 레지스트리는 세션 생명주기를, 이 클래스는 카운터와 admission 판단을 맡는다.
// 카운터는 ConcurrentHashMap + AtomicInteger로 낙관적으로 올리고, 거절 시 롤백한다.
class WebSocketConnectionLimiter {
    private final Map<String, AtomicInteger> connectionsByIp = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> connectionsByUser = new ConcurrentHashMap<>();
    private final Map<String, AttemptWindow> attemptsByIp = new ConcurrentHashMap<>();
    private final AtomicInteger globalConnections = new AtomicInteger();

    // 신규 연결 admission: 시도율 → 전역 → IP 한도를 순서대로 확인하고, 거절 시 자체 예약을 롤백한다
    // 거절(false)된 세션을 닫는 것은 호출자 책임이다.
    boolean tryAcquireConnection(String ipAddress, int maxConnectionAttemptsPerMinute, int maxGlobalConnections, int maxConnectionsPerIp) {
        if (!recordConnectionAttempt(ipAddress, maxConnectionAttemptsPerMinute)) {
            return false;
        }
        if (globalConnections.incrementAndGet() > maxGlobalConnections) {
            globalConnections.decrementAndGet();
            return false;
        }
        AtomicInteger ipConnections = connectionsByIp.computeIfAbsent(ipAddress, ignored -> new AtomicInteger());
        if (ipConnections.incrementAndGet() > maxConnectionsPerIp) {
            globalConnections.decrementAndGet();
            decrement(connectionsByIp, ipAddress);
            return false;
        }
        return true;
    }

    // 연결 종료 시 전역/IP/사용자 카운터를 모두 해제한다
    void releaseConnection(String ipAddress, Long userId) {
        globalConnections.decrementAndGet();
        decrement(connectionsByIp, ipAddress);
        if (userId != null) {
            decrement(connectionsByUser, userId);
        }
    }

    // 사용자 인증 시 per-user 연결 한도를 확인하고, 초과 시 롤백 후 false를 반환한다
    boolean tryAcquireUser(Long userId, int maxConnectionsPerUser) {
        AtomicInteger userConnections = connectionsByUser.computeIfAbsent(userId, ignored -> new AtomicInteger());
        if (userConnections.incrementAndGet() > maxConnectionsPerUser) {
            decrement(connectionsByUser, userId);
            return false;
        }
        return true;
    }

    // 시작 후 60초가 지난 IP별 연결 시도 윈도우를 제거하고 제거한 수를 반환한다.
    int purgeExpiredAttempts(Instant now) {
        int removed = 0;
        for (Map.Entry<String, AttemptWindow> entry : attemptsByIp.entrySet()) {
            AttemptWindow window = entry.getValue();
            if (Duration.between(window.windowStart(), now).toSeconds() >= 60
                    && attemptsByIp.remove(entry.getKey(), window)) {
                removed++;
            }
        }
        return removed;
    }

    // IP별 1분 윈도우의 연결 시도 횟수를 올리고(만료 시 새 윈도우 시작), 한도 이내인지 반환한다.
    private boolean recordConnectionAttempt(String ipAddress, int maxConnectionAttemptsPerMinute) {
        AttemptWindow window = attemptsByIp.compute(ipAddress, (ignored, current) -> {
            Instant now = Instant.now();
            if (current == null || Duration.between(current.windowStart(), now).toSeconds() >= 60) {
                return new AttemptWindow(now, 1);
            }
            return current.increment();
        });
        return window.count() <= maxConnectionAttemptsPerMinute;
    }

    // 카운터를 1 줄이고, 0 이하가 되면 맵에서 키를 제거한다.
    private static <T> void decrement(Map<T, AtomicInteger> counters, T key) {
        counters.computeIfPresent(key, (ignored, value) -> value.decrementAndGet() <= 0 ? null : value);
    }

    // IP별 1분 연결 시도 윈도우(시작 시각, 시도 횟수)
    private record AttemptWindow(Instant windowStart, int count) {
        AttemptWindow increment() {
            return new AttemptWindow(windowStart, count + 1);
        }
    }
}
