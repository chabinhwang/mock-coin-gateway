package com.mockcoin.gateway.websocket.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.mockcoin.gateway.websocket.config.GatewayWebSocketProperties;
import com.mockcoin.gateway.websocket.domain.PrivateGatewayEvent;
import com.mockcoin.gateway.websocket.domain.PublicGatewayEvent;
import com.mockcoin.observability.MockcoinMetrics;
import com.mockcoin.shared.api.CommonErrorCode;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

// 등록된 WebSocket 구독자에게 public/private 이벤트를 bounded virtual thread fanout으로 전달한다
@Service
public class GatewayWebSocketBroadcaster {
    // 최대 fanoutConcurrency개의 worker VT가 구독자를 분담하고 짧은 join 윈도우까지만 전송 결과를 모은다.
    // ConcurrentWebSocketSessionDecorator가 세션별 send를 직렬화/큐잉하며, slow worker는 deadline에 취소된다.
    private static final long FANOUT_JOIN_TIMEOUT_MILLIS = 1_500L;

    private final GatewayWebSocketSessionRegistry sessions;
    private final int fanoutConcurrency;
    private final MockcoinMetrics metrics;
    private final Semaphore fanoutWorkerPermits;
    // 장수(long-lived) virtual-thread executor — broadcast 호출마다 ExecutorService를 만들고 close 하면서
    // 묵시적 awaitTermination 비용이 발생하던 회귀를 제거.
    private final ExecutorService fanoutExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // 의존성을 주입받고, fanout 동시성 설정만큼 worker 허가를 가진 공정 세마포어를 만든다.
    public GatewayWebSocketBroadcaster(
            GatewayWebSocketSessionRegistry sessions,
            GatewayWebSocketProperties properties,
            MockcoinMetrics metrics
    ) {
        this.sessions = sessions;
        this.fanoutConcurrency = properties.fanoutConcurrency();
        this.metrics = metrics;
        this.fanoutWorkerPermits = new Semaphore(fanoutConcurrency, true);
    }

    // ticker/orderbook/candle/marketList 채널 구독자에게 public 이벤트를 전송한다
    public int broadcastPublic(PublicGatewayEvent event) {
        String channel = channelFor(event.type());
        boolean marketScoped = !"marketList".equals(channel);
        String market = marketScoped ? event.market() : null;
        String interval = "candle".equals(channel) ? candleInterval(event.data()) : null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", event.type());
        // 회귀 #7: prev-server `WebSocketBroadcaster.broadcastMarketListUpdate` 의 wire payload 는
        // {type, ts, data} 만 포함하고 market 필드를 두지 않았다. marketList 채널은 market-scoped 가
        // 아니므로 wire 의미를 보존하기 위해 marketList.update 일 때 market 필드 자체를 omit 한다.
        if (marketScoped) {
            payload.put("market", event.market());
        }
        payload.put("ts", timestamp(event.ts()));
        payload.put("data", event.data() == null ? Map.of() : event.data());
        return sendBounded(sessions.publicSubscribers(channel, market, interval), payload);
    }

    // 특정 사용자의 order/portfolio 구독자에게 private 이벤트를 전송한다
    public int broadcastPrivate(Long userId, PrivateGatewayEvent event) {
        String channel = channelFor(event.type());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", event.type());
        payload.put("ts", timestamp(event.ts()));
        payload.put("data", event.data() == null ? Map.of() : event.data());
        return sendBounded(sessions.privateSubscribers(userId, channel), payload);
    }

    // payload를 한 번만 직렬화해 최대 fanoutConcurrency개 worker로 나눠 보내고, 1.5초 마감까지만 기다린다.
    // 마감까지 못 끝난 worker는 취소하고, 못 보낸 수만큼 drop 메트릭을 올린 뒤 전송 성공 수를 반환한다.
    private int sendBounded(List<GatewayWebSocketSessionRegistry.ClientSession> subscribers, Map<String, Object> payload) {
        if (subscribers.isEmpty()) {
            return 0;
        }
        // payload는 한 번만 직렬화한다 — broadcast당 1회 직렬화 보장.
        String serialized = sessions.serialize(payload);
        if (serialized == null) {
            for (int i = 0; i < subscribers.size(); i++) {
                metrics.wsMessageDropped();
            }
            return 0;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FANOUT_JOIN_TIMEOUT_MILLIS);
        AtomicInteger nextSubscriber = new AtomicInteger();
        AtomicInteger sent = new AtomicInteger();
        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(fanoutExecutor);
        List<Future<Void>> futures = new ArrayList<>(Math.min(fanoutConcurrency, subscribers.size()));
        int desiredWorkers = Math.min(fanoutConcurrency, subscribers.size());
        for (int i = 0; i < desiredWorkers; i++) {
            futures.add(submitFanoutWorker(
                    completionService,
                    subscribers,
                    serialized,
                    nextSubscriber,
                    sent,
                    deadline
            ));
        }
        int completed = 0;
        while (completed < futures.size()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            Future<Void> completedFuture;
            try {
                completedFuture = completionService.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (completedFuture == null) {
                    break;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            completed++;
            try {
                completedFuture.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException ignored) {
                // 한 세션 전송 실패/지연은 다른 세션의 전송에 영향을 주지 않는다.
            }
        }
        for (Future<Void> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        int delivered = sent.get();
        for (int i = delivered; i < subscribers.size(); i++) {
            metrics.wsMessageDropped();
        }
        return delivered;
    }

    // 마감 전에 worker 허가를 얻은 경우에만 fanout worker를 실행하는 작업을 제출하고, 끝나면 허가를 돌려준다.
    private Future<Void> submitFanoutWorker(
            ExecutorCompletionService<Void> completionService,
            List<GatewayWebSocketSessionRegistry.ClientSession> subscribers,
            String serialized,
            AtomicInteger nextSubscriber,
            AtomicInteger sent,
            long deadline
    ) {
        return completionService.submit(() -> {
            boolean acquired = false;
            try {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0
                        || !fanoutWorkerPermits.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
                    return null;
                }
                acquired = true;
                runFanoutWorker(subscribers, serialized, nextSubscriber, sent, deadline);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) {
                    fanoutWorkerPermits.release();
                }
            }
            return null;
        });
    }

    // 공유 인덱스로 다음 구독자를 가져가며 마감·중단 전까지 보내고, 마감 안에 보낸 건만 성공으로 센다.
    private void runFanoutWorker(
            List<GatewayWebSocketSessionRegistry.ClientSession> subscribers,
            String serialized,
            AtomicInteger nextSubscriber,
            AtomicInteger sent,
            long deadline
    ) {
        while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
            int index = nextSubscriber.getAndIncrement();
            if (index >= subscribers.size()) {
                break;
            }
            if (sendSafely(subscribers.get(index), serialized) && System.nanoTime() <= deadline) {
                sent.incrementAndGet();
            }
        }
    }

    // 세션에 직렬화된 메시지를 보내고, 예외가 나면 false를 반환한다.
    private boolean sendSafely(GatewayWebSocketSessionRegistry.ClientSession subscriber, String serialized) {
        try {
            sessions.sendSerialized(subscriber.session(), serialized);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // 빈 종료 시 fanout 실행기를 종료한다.
    @PreDestroy
    void shutdown() {
        fanoutExecutor.shutdown();
    }

    // 시각을 epoch 밀리초로 바꾸고, 없으면 현재 시각을 쓴다.
    private static long timestamp(Instant instant) {
        return (instant == null ? Instant.now() : instant).toEpochMilli();
    }

    // public 이벤트 타입이 ticker/orderbook/candle/marketList 중 하나가 아니면 INVALID_PARAMETER 예외를 던진다.
    public static void verifyPublicEventType(PublicGatewayEvent event) {
        if (event == null || !isPublicEventType(event.type())) {
            throw CommonErrorCode.INVALID_PARAMETER.exception("지원하지 않는 WebSocket public event type입니다.");
        }
    }

    // 지원하는 public 이벤트 타입인지 확인한다.
    private static boolean isPublicEventType(String type) {
        return "ticker.update".equals(type)
                || "orderbook.snapshot".equals(type)
                || "candle.reconcile".equals(type)
                || "marketList.update".equals(type);
    }

    // 이벤트 타입을 WebSocket 채널 이름으로 바꾸고, 모르는 타입이면 INVALID_PARAMETER 예외를 던진다.
    private static String channelFor(String type) {
        return switch (type) {
            case "ticker.update" -> "ticker";
            case "orderbook.snapshot" -> "orderbook";
            case "candle.reconcile" -> "candle";
            case "marketList.update" -> "marketList";
            case "order.update" -> "order";
            case "portfolio.update" -> "portfolio";
            default -> throw CommonErrorCode.INVALID_PARAMETER.exception("지원하지 않는 WebSocket event type입니다.");
        };
    }

    // data의 interval 문자열을 꺼내고, 없거나 비어 있으면 null을 반환한다.
    private static String candleInterval(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Object value = data.get("interval");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

}
