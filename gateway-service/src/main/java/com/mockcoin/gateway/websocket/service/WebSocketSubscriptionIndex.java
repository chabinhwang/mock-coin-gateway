package com.mockcoin.gateway.websocket.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mockcoin.gateway.websocket.service.GatewayWebSocketSessionRegistry.ClientSession;

// 채널/마켓/인터벌·사용자별 구독 역인덱스 — public/private fanout O(1) lookup 담당
// 구독 키는 public이면 channel:market[:interval], private이면 channel:u:userId이며, candle은 와일드카드 fallback 조회를 한다.
// GatewayWebSocketSessionRegistry에서 분리해 레지스트리는 세션 생명주기를, 이 클래스는 구독 라우팅을 맡는다.
class WebSocketSubscriptionIndex {
    // 인덱스: subscriptionKey -> 구독 중인 ClientSession 집합
    private final Map<String, Set<ClientSession>> subscribersByKey = new ConcurrentHashMap<>();

    // 구독 키에 세션을 등록한다
    void register(ClientSession client, String key) {
        subscribersByKey
                .computeIfAbsent(key, ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(client);
    }

    // 구독 키에서 세션을 제거하고, 비면 키 자체를 제거한다
    void unregister(ClientSession client, String key) {
        Set<ClientSession> set = subscribersByKey.get(key);
        if (set == null) {
            return;
        }
        set.remove(client);
        if (set.isEmpty()) {
            subscribersByKey.remove(key, set);
        }
    }

    // public 채널 구독자를 조회한다(candle은 interval exact + wildcard fallback 병합)
    List<ClientSession> publicSubscribers(String channel, String market, String interval) {
        String normalizedMarket = "marketList".equals(channel) ? null : GatewayWebSocketSessionRegistry.normalizeMarket(market);
        Set<ClientSession> exact = subscribersByKey.get(subscriptionKey(channel, normalizedMarket, interval));
        Set<ClientSession> wildcard = "candle".equals(channel) && interval != null && !interval.isBlank()
                ? subscribersByKey.get(subscriptionKey(channel, normalizedMarket, null))
                : null;
        if ((exact == null || exact.isEmpty()) && (wildcard == null || wildcard.isEmpty())) {
            return List.of();
        }
        Set<ClientSession> merged = new HashSet<>();
        if (exact != null) {
            merged.addAll(exact);
        }
        if (wildcard != null) {
            merged.addAll(wildcard);
        }
        return new ArrayList<>(merged);
    }

    // 특정 사용자의 private 채널 구독자를 조회한다
    List<ClientSession> privateSubscribers(Long userId, String channel) {
        if (userId == null) {
            return List.of();
        }
        Set<ClientSession> set = subscribersByKey.get(privateSubscriptionKey(channel, userId));
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(set);
    }

    // channel:market 형식의 구독 키를 만들고, market이 없으면 '*'를 쓴다.
    static String subscriptionKey(String channel, String market) {
        return channel + ":" + (market == null ? "*" : GatewayWebSocketSessionRegistry.normalizeMarket(market));
    }

    // candle 채널이고 interval이 있으면 구독 키 끝에 :interval을 붙인다.
    static String subscriptionKey(String channel, String market, String interval) {
        String base = subscriptionKey(channel, market);
        if (!"candle".equals(channel) || interval == null || interval.isBlank()) {
            return base;
        }
        return base + ":" + interval;
    }

    // channel:u:userId 형식의 private 구독 키를 만든다.
    static String privateSubscriptionKey(String channel, Long userId) {
        // prev-server SubscriptionManager.privateKey와 동일 의미: channel + ":u:" + userId.
        // userId 차원을 키에 포함해 fanout이 전 private 구독자 순회 없이 직접 lookup으로 동작.
        return channel + ":u:" + userId;
    }
}
