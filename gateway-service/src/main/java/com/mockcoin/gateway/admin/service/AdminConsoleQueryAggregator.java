package com.mockcoin.gateway.admin.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.mockcoin.gateway.admin.integration.AdminDownstreamClient;
import com.mockcoin.shared.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// 관리자 탭 조회 중 여러 서비스 데이터를 페이지 행 기준으로 합치는 조회 집계기
// 행과 페이징은 user-service가 소유하고, trading-service 데이터(지갑·포트폴리오·시즌 이름)와 닉네임은 해당 페이지 행에만 붙여
// 값이 잘린 전역 목록에 좌우되지 않게 한다.
@Service
public class AdminConsoleQueryAggregator {
    private static final Logger log = LoggerFactory.getLogger(AdminConsoleQueryAggregator.class);
    private static final int NICKNAME_BATCH_LIMIT = 100;

    private final AdminDownstreamClient downstreamClient;
    private final ObjectMapper objectMapper;

    // 관리자 downstream 클라이언트와 ObjectMapper를 주입받는다.
    public AdminConsoleQueryAggregator(AdminDownstreamClient downstreamClient, ObjectMapper objectMapper) {
        this.downstreamClient = downstreamClient;
        this.objectMapper = objectMapper;
    }

    // 사용자 검색 페이지에 해당 사용자들의 지갑을 붙인다. 지갑 조회 실패 시 목록은 유지하고 walletLookupFailed로 알린다
    public ObjectNode users(String query, String cursor, Integer limit) {
        JsonNode page = downstreamClient.userQuery(AdminQueryString.path("/internal/user-service/admin/users")
                .param("q", query)
                .param("cursor", cursor)
                .param("limit", limit)
                .build());
        ObjectNode result = objectCopy(page);
        ArrayNode items = arrayOrEmpty(page.path("items"));
        List<Long> userIds = longValues(items, "userId");
        Map<Long, JsonNode> wallets;
        boolean walletLookupFailed = false;
        try {
            wallets = walletsByUserId(userIds);
        } catch (ApiException exception) {
            log.warn("Admin user page wallet lookup failed reason={}", exception.getMessage());
            wallets = Map.of();
            walletLookupFailed = true;
        }
        result.set("items", mergeWallets(items, wallets, walletLookupFailed));
        result.put("walletLookupFailed", walletLookupFailed);
        return result;
    }

    // 사용자 상세: 계정 요약(필수, 없으면 404 전달) + 지갑·보유 종목(선택)
    public ObjectNode userDetail(Long userId) {
        JsonNode user = downstreamClient.userQuery("/internal/user-service/admin/users/" + userId);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("user", user);
        try {
            result.set("portfolio", downstreamClient.tradingQuery("/internal/trading-service/admin/users/" + userId + "/portfolio"));
            result.put("portfolioUnavailable", false);
        } catch (ApiException exception) {
            log.warn("Admin user detail portfolio lookup failed userId={} reason={}", userId, exception.getMessage());
            result.putNull("portfolio");
            result.put("portfolioUnavailable", true);
        }
        return result;
    }

    // 지갑 초기화 이력 페이지에 시즌 이름을 붙인다 (시즌 조회 실패 시 이름만 비운다)
    public ObjectNode resetHistories(Long userId, String cursor, Integer limit) {
        JsonNode page = downstreamClient.userQuery(AdminQueryString.path("/internal/user-service/admin/reset-histories")
                .param("userId", userId)
                .param("cursor", cursor)
                .param("limit", limit)
                .build());
        ObjectNode result = objectCopy(page);
        Map<Long, String> seasonNames = seasonNames();
        ArrayNode merged = objectMapper.createArrayNode();
        arrayOrEmpty(page.path("items")).forEach(history -> {
            ObjectNode row = objectCopy(history);
            String name = seasonNames.get(history.path("seasonId").asLong(-1));
            if (name == null) {
                row.putNull("seasonName");
            } else {
                row.put("seasonName", name);
            }
            merged.add(row);
        });
        result.set("items", merged);
        return result;
    }

    // 시즌 랭킹 페이지에 닉네임을 붙인다 (닉네임 조회 실패 시 비워 둔다)
    public ObjectNode seasonRankings(Long seasonId, String cursor, Integer limit) {
        JsonNode page = downstreamClient.tradingQuery(AdminQueryString.path("/internal/trading-service/admin/ranking/seasons/" + seasonId + "/rankings")
                .param("cursor", cursor)
                .param("limit", limit)
                .build());
        ObjectNode result = objectCopy(page);
        result.set("rankings", withNicknames(arrayOrEmpty(page.path("rankings"))));
        return result;
    }

    // 행의 userId 기준으로 nickname 필드를 채운다. 이미 nickname이 있는 행은 유지한다
    public ArrayNode withNicknames(ArrayNode rows) {
        Map<Long, String> nicknames = nicknames(longValues(rows, "userId"));
        ArrayNode merged = objectMapper.createArrayNode();
        rows.forEach(row -> {
            ObjectNode copy = objectCopy(row);
            if (!copy.path("nickname").isTextual()) {
                String nickname = nicknames.get(row.path("userId").asLong(-1));
                if (nickname == null) {
                    copy.putNull("nickname");
                } else {
                    copy.put("nickname", nickname);
                }
            }
            merged.add(copy);
        });
        return merged;
    }

    // userId 목록의 지갑을 trading-service에서 정확히 조회한다
    public Map<Long, JsonNode> walletsByUserId(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String ids = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        JsonNode response = downstreamClient.tradingQuery(AdminQueryString.path("/internal/trading-service/admin/wallets")
                .param("userIds", ids)
                .build());
        Map<Long, JsonNode> wallets = new HashMap<>();
        arrayOrEmpty(response.path("wallets")).forEach(wallet -> wallets.put(wallet.path("userId").asLong(), wallet));
        return wallets;
    }

    // 지갑 조회 결과를 사용자 행에 붙인다. 조회 실패면 hasWallet을 null(알 수 없음)로 둔다
    public ArrayNode mergeWallets(ArrayNode users, Map<Long, JsonNode> wallets, boolean lookupFailed) {
        ArrayNode merged = objectMapper.createArrayNode();
        users.forEach(user -> {
            ObjectNode row = objectCopy(user);
            JsonNode wallet = wallets.get(user.path("userId").asLong(-1));
            if (lookupFailed) {
                row.putNull("hasWallet");
            } else {
                row.put("hasWallet", wallet != null);
            }
            if (wallet == null) {
                row.putNull("seedKrwBalance");
                row.putNull("krwBalance");
            } else {
                row.set("seedKrwBalance", wallet.path("seedKrwBalance"));
                row.set("krwBalance", wallet.path("krwBalance"));
            }
            merged.add(row);
        });
        return merged;
    }

    // trading-service 시즌 목록을 seasonId→이름 맵으로 만들고, 조회 실패 시 빈 맵을 반환한다.
    private Map<Long, String> seasonNames() {
        try {
            JsonNode seasons = downstreamClient.tradingQuery("/internal/trading-service/admin/ranking/seasons");
            Map<Long, String> names = new HashMap<>();
            arrayOrEmpty(seasons.path("seasons")).forEach(season ->
                    names.put(season.path("seasonId").asLong(), season.path("name").asText(null)));
            return names;
        } catch (ApiException exception) {
            log.warn("Admin season name lookup failed reason={}", exception.getMessage());
            return Map.of();
        }
    }

    // 중복을 뺀 userId를 100개씩 나눠 user-service에서 닉네임을 조회하고, 실패한 배치는 건너뛴다.
    private Map<Long, String> nicknames(List<Long> userIds) {
        Map<Long, String> nicknames = new HashMap<>();
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(userIds));
        for (int start = 0; start < distinct.size(); start += NICKNAME_BATCH_LIMIT) {
            List<Long> batch = distinct.subList(start, Math.min(start + NICKNAME_BATCH_LIMIT, distinct.size()));
            try {
                JsonNode response = downstreamClient.userQuery(AdminQueryString.path("/internal/user-service/users/nicknames")
                        .param("ids", batch.stream().map(String::valueOf).collect(Collectors.joining(",")))
                        .build());
                response.path("nicknames").properties().forEach(entry -> {
                    try {
                        nicknames.put(Long.parseLong(entry.getKey()), entry.getValue().asText());
                    } catch (NumberFormatException ignored) {
                        // user-service는 숫자 키만 반환한다. 형식이 다르면 해당 행만 건너뛴다
                    }
                });
            } catch (ApiException exception) {
                log.warn("Admin nickname lookup failed reason={}", exception.getMessage());
            }
        }
        return nicknames;
    }

    // 객체 노드면 깊은 복사본을, 아니면 빈 객체 노드를 반환한다.
    private ObjectNode objectCopy(JsonNode node) {
        return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : objectMapper.createObjectNode();
    }

    // 배열 노드면 그대로, 아니면 빈 배열 노드를 반환한다.
    private ArrayNode arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : objectMapper.createArrayNode();
    }

    // 행들의 field 숫자 값을 순서를 유지하며 중복 없이 모은다.
    private static List<Long> longValues(JsonNode rows, String field) {
        Set<Long> values = new LinkedHashSet<>();
        rows.forEach(row -> {
            JsonNode value = row.path(field);
            if (value.isNumber()) {
                values.add(value.asLong());
            }
        });
        return List.copyOf(values);
    }
}
