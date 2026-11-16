package com.mockcoin.gateway.admin.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.integration.AdminDownstreamClient;
import com.mockcoin.gateway.global.error.GatewayErrorCode;
import com.mockcoin.gateway.global.security.GatewaySecurityHardeningProperties;
import com.mockcoin.gateway.websocket.config.GatewayRedisMarketEventProperties;
import com.mockcoin.gateway.websocket.config.GatewayRedisPrivateEventProperties;
import com.mockcoin.gateway.websocket.config.GatewayWebSocketProperties;
import com.mockcoin.shared.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// user 필수 + trading/market/ingestor/community 선택 fragment를 병렬 조회·머지해 대시보드 overview를 구성한다
// 선택 fragment는 한 서비스가 느리거나 실패해도 대시보드 전체가 비지 않고 일부만 빠지게 하며,
// 목록은 짧은 '최근 N개'만 담고 각 탭은 자체 페이지 API로 불러온다.
@Service
public class AdminOverviewAggregator {
    private static final Logger log = LoggerFactory.getLogger(AdminOverviewAggregator.class);

    private final AdminDownstreamClient downstreamClient;
    private final AdminConsoleQueryAggregator queryAggregator;
    private final ObjectMapper objectMapper;
    private final GatewayAdminProperties adminProperties;
    private final GatewaySecurityHardeningProperties securityProperties;
    private final GatewayWebSocketProperties websocketProperties;
    private final GatewayRedisMarketEventProperties redisMarketEventProperties;
    private final GatewayRedisPrivateEventProperties redisPrivateEventProperties;

    // downstream 클라이언트, 조회 집계기, ObjectMapper와 관리자·보안·WebSocket·Redis 이벤트 설정을 주입받는다.
    public AdminOverviewAggregator(
            AdminDownstreamClient downstreamClient,
            AdminConsoleQueryAggregator queryAggregator,
            ObjectMapper objectMapper,
            GatewayAdminProperties adminProperties,
            GatewaySecurityHardeningProperties securityProperties,
            GatewayWebSocketProperties websocketProperties,
            GatewayRedisMarketEventProperties redisMarketEventProperties,
            GatewayRedisPrivateEventProperties redisPrivateEventProperties
    ) {
        this.downstreamClient = downstreamClient;
        this.queryAggregator = queryAggregator;
        this.objectMapper = objectMapper;
        this.adminProperties = adminProperties;
        this.securityProperties = securityProperties;
        this.websocketProperties = websocketProperties;
        this.redisMarketEventProperties = redisMarketEventProperties;
        this.redisPrivateEventProperties = redisPrivateEventProperties;
    }

    // user 필수 fragment + 나머지 optional fragment를 병렬 조회해 overview를 합친다
    public ObjectNode overview() {
        ArrayNode degraded = objectMapper.createArrayNode();
        JsonNode user;
        JsonNode trading;
        JsonNode market;
        JsonNode ingestor;
        JsonNode community;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JsonNode> userFuture = executor.submit(downstreamClient::userOverview);
            Future<JsonNode> tradingFuture = executor.submit(downstreamClient::tradingOverview);
            Future<JsonNode> marketFuture = executor.submit(downstreamClient::marketOverview);
            Future<JsonNode> ingestorFuture = executor.submit(downstreamClient::ingestorOverview);
            Future<JsonNode> communityFuture = executor.submit(downstreamClient::communityOverview);
            user = requiredFragment(userFuture, "user-service");
            trading = optionalFragment(tradingFuture, "trading-service", degraded);
            market = optionalFragment(marketFuture, "market-service", null);
            ingestor = optionalFragment(ingestorFuture, "ingestor-service", null);
            community = optionalFragment(communityFuture, "community-service", degraded);
        }
        ObjectNode overview = objectMapper.createObjectNode();
        overview.set("runtime", runtime(user.path("runtime"), trading.path("runtime"), market.path("runtime"), ingestor.path("runtime"), community.path("runtime")));
        overview.set("operatorNotes", operatorNotes());
        overview.set("billingProducts", user.path("billingProducts"));
        overview.set("rankingSeasons", trading.path("rankingSeasons"));
        overview.set("users", recentUsersWithWallets(user.path("users"), degraded));
        overview.set("paymentOrders", user.path("paymentOrders"));
        overview.put("activeSeasonId", trading.path("activeSeasonId").isMissingNode() || trading.path("activeSeasonId").isNull() ? null : trading.path("activeSeasonId").asLong());
        overview.set("activeSeasonRankings", trading.path("activeSeasonRankings").isArray()
                ? queryAggregator.withNicknames((ArrayNode) trading.path("activeSeasonRankings"))
                : objectMapper.createArrayNode());
        overview.set("billingProductTypes", user.path("billingProductTypes"));
        overview.set("seasonStatuses", trading.path("seasonStatuses"));
        overview.set("counts", counts(user.path("counts"), trading.path("counts"), community.path("counts")));
        overview.set("degradedServices", degraded);
        return overview;
    }

    // 콘솔에 보여 줄 운영 안내 목록을 만든다(패스키 사용 여부에 따라 관리자 권한 안내가 달라진다).
    private ArrayNode operatorNotes() {
        ArrayNode notes = objectMapper.createArrayNode();
        notes.add(operatorNote("앱 등록", "Apps in Toss console appName은 반드시 'mock-coin'으로 등록되어야 합니다."));
        notes.add(operatorNote("약관 링크", "모바일 로그인 약관 URL: https://mock-coin.pages.dev/"));
        notes.add(operatorNote("IAP SKU 정렬", "활성 billing_products.provider_sku, Toss IAP SKU, 모바일 기본 SKU가 일치해야 합니다."));
        notes.add(operatorNote(
                "운영 관리자 권한",
                adminProperties.passkeyEnabled()
                        ? "/admin/api/**는 gateway password/Passkey 인증으로 발급한 admin session cookie가 필요합니다."
                        : "/admin/api/**는 gateway password 인증으로 발급한 admin session cookie가 필요합니다."
        ));
        notes.add(operatorNote("런타임 설정", "Runtime 섹션은 읽기 전용입니다. env/application 변경 시 재시작이 필요합니다."));
        notes.add(operatorNote("원장성 데이터", "결제/랭킹/초기화 로그는 주로 읽기 전용이며, 전용 액션을 통해서만 변경 가능합니다."));
        return notes;
    }

    // 제목과 설명을 담은 운영자 안내 노드를 만든다.
    private ObjectNode operatorNote(String title, String detail) {
        ObjectNode note = objectMapper.createObjectNode();
        note.put("title", title);
        note.put("detail", detail);
        return note;
    }

    // 필수 fragment 결과를 기다리고, ApiException은 그대로, 그 밖의 실패·중단은 DOWNSTREAM_ADMIN_OVERVIEW_FAILED로 던진다.
    private JsonNode requiredFragment(Future<JsonNode> future, String serviceName) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw GatewayErrorCode.DOWNSTREAM_ADMIN_OVERVIEW_FAILED.exception(serviceName + " overview 호출이 중단되었습니다.");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ApiException apiException) {
                throw apiException;
            }
            throw GatewayErrorCode.DOWNSTREAM_ADMIN_OVERVIEW_FAILED.exception(serviceName + " overview 호출에 실패했습니다.");
        }
    }

    // 실패하면 빈 fragment로 대체한다. degraded 목록을 주면 실패한 서비스 이름을 기록해 화면에 알린다
    private JsonNode optionalFragment(Future<JsonNode> future, String serviceName, ArrayNode degraded) {
        try {
            JsonNode fragment = future.get();
            if (fragment == null || fragment.isEmpty()) {
                markDegraded(degraded, serviceName);
            }
            return fragment == null ? objectMapper.createObjectNode() : fragment;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Admin overview optional fragment interrupted service={}", serviceName);
            markDegraded(degraded, serviceName);
            return objectMapper.createObjectNode();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            log.warn("Admin overview optional fragment failed service={} reason={}", serviceName,
                    cause != null ? cause.getMessage() : exception.getMessage());
            markDegraded(degraded, serviceName);
            return objectMapper.createObjectNode();
        }
    }

    // degraded 목록이 주어졌고 아직 없는 서비스일 때만 이름을 추가한다.
    private static void markDegraded(ArrayNode degraded, String serviceName) {
        if (degraded == null) {
            return;
        }
        for (JsonNode existing : degraded) {
            if (serviceName.equals(existing.asText())) {
                return;
            }
        }
        degraded.add(serviceName);
    }

    // 최근 가입 사용자에게 해당 사용자들의 지갑만 정확히 조회해 붙인다
    private ArrayNode recentUsersWithWallets(JsonNode users, ArrayNode degraded) {
        ArrayNode rows = users.isArray() ? (ArrayNode) users : objectMapper.createArrayNode();
        List<Long> userIds = new ArrayList<>();
        rows.forEach(row -> {
            if (row.path("userId").isNumber()) {
                userIds.add(row.path("userId").asLong());
            }
        });
        try {
            return queryAggregator.mergeWallets(rows, queryAggregator.walletsByUserId(userIds), false);
        } catch (ApiException exception) {
            log.warn("Admin overview wallet lookup failed reason={}", exception.getMessage());
            markDegraded(degraded, "trading-service");
            return queryAggregator.mergeWallets(rows, Map.of(), true);
        }
    }

    // 서비스별 runtime fragment와 gateway 보안·WebSocket·Redis 이벤트 설정을 합쳐 runtime 섹션을 만든다.
    private ObjectNode runtime(JsonNode userRuntime, JsonNode tradingRuntime, JsonNode marketRuntime, JsonNode ingestorRuntime, JsonNode communityRuntime) {
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.set("auth", userRuntime.path("auth"));
        runtime.set("billing", userRuntime.path("billing"));
        runtime.set("order", tradingRuntime.path("order"));
        ObjectNode security = objectMapper.createObjectNode();
        security.put("rateLimitEnabled", securityProperties.isRateLimitEnabled());
        security.put("ipRequestsPerMinute", securityProperties.getIpRequestsPerMinute());
        security.put("orderRequestsPerMinute", securityProperties.getOrderRequestsPerMinute());
        ArrayNode corsAllowedOrigins = objectMapper.createArrayNode();
        securityProperties.getCorsAllowedOrigins().forEach(corsAllowedOrigins::add);
        security.set("corsAllowedOrigins", corsAllowedOrigins);
        security.put("secureAdminCookie", adminProperties.secureCookie());
        security.put("adminSessionTimeoutSeconds", GatewayAdminProperties.SESSION_TIMEOUT_SECONDS);
        security.put("adminMaxLoginAttempts", GatewayAdminProperties.MAX_LOGIN_ATTEMPTS);
        security.put("adminLockoutDurationSeconds", GatewayAdminProperties.LOCKOUT_DURATION_SECONDS);
        security.put("passkeyEnabled", adminProperties.passkeyEnabled());
        security.put("webauthnRpId", adminProperties.webauthnRpId());
        security.put("webauthnOriginsConfigured", !adminProperties.webauthnOrigins().isEmpty());
        runtime.set("security", security);
        ObjectNode websocket = objectMapper.createObjectNode();
        websocket.put("authTimeoutSeconds", websocketProperties.authTimeoutSeconds());
        websocket.put("pingIntervalSeconds", websocketProperties.pingIntervalSeconds());
        websocket.put("pongTimeoutSeconds", websocketProperties.pongTimeoutSeconds());
        websocket.put("heartbeatCheckIntervalMillis", websocketProperties.heartbeatCheckIntervalMillis());
        websocket.put("maxMissedPongs", websocketProperties.maxMissedPongs());
        websocket.put("maxMessageBytes", websocketProperties.maxMessageBytes());
        websocket.put("maxGlobalConnections", websocketProperties.maxGlobalConnections());
        websocket.put("maxConnectionsPerIp", websocketProperties.maxConnectionsPerIp());
        websocket.put("maxConnectionsPerUser", websocketProperties.maxConnectionsPerUser());
        websocket.put("maxConnectionAttemptsPerMinute", websocketProperties.maxConnectionAttemptsPerMinute());
        websocket.put("maxMessagesPerSecond", websocketProperties.maxMessagesPerSecond());
        websocket.put("maxSubscriptionsPerSession", websocketProperties.maxSubscriptionsPerSession());
        runtime.set("websocket", websocket);
        ObjectNode integrations = objectMapper.createObjectNode();
        userRuntime.path("integrations").properties().forEach(entry -> integrations.set(entry.getKey(), entry.getValue()));
        marketRuntime.path("integrations").properties().forEach(entry -> integrations.set(entry.getKey(), entry.getValue()));
        ingestorRuntime.path("integrations").properties().forEach(entry -> integrations.set(entry.getKey(), entry.getValue()));
        communityRuntime.path("integrations").properties().forEach(entry -> integrations.set(entry.getKey(), entry.getValue()));
        integrations.put("gatewayRedisMarketEventsEnabled", redisMarketEventProperties.enabled());
        integrations.put("gatewayRedisMarketEventsChannelPrefix", redisMarketEventProperties.channelPrefix());
        integrations.put("gatewayRedisPrivateEventsEnabled", redisPrivateEventProperties.enabled());
        integrations.put("gatewayRedisPrivateEventsChannelPrefix", redisPrivateEventProperties.channelPrefix());
        integrations.put("gatewayInternalEventTokenConfigured", !websocketProperties.internalEventToken().isBlank());
        runtime.set("integrations", integrations);
        return runtime;
    }

    // user·trading·community 집계 필드를 한 객체로 합친다(같은 키는 뒤 서비스 값이 덮는다).
    private ObjectNode counts(JsonNode userCounts, JsonNode tradingCounts, JsonNode communityCounts) {
        ObjectNode counts = objectMapper.createObjectNode();
        copyFields(userCounts, counts);
        copyFields(tradingCounts, counts);
        copyFields(communityCounts, counts);
        return counts;
    }

    // source의 모든 필드를 target에 덮어써 복사한다.
    private static void copyFields(JsonNode source, ObjectNode target) {
        Iterator<Map.Entry<String, JsonNode>> fields = source.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            target.set(entry.getKey(), entry.getValue());
        }
    }
}
