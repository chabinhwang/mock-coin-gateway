package com.mockcoin.shared.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import io.swagger.v3.oas.annotations.media.Schema;

/** 업비트 조회와 저장이 모두 완료된 닫힌 1분봉 구간. 빈 목록도 확인된 무거래 구간이다. */
@Schema(description = "업비트 조회와 저장이 모두 끝난 1분봉 구간 `[from, to)`. 빈 `candles`는 거래가 없던 것으로 확인된 구간이다.")
public record CandleRecoveryRange(
        @Schema(description = "마켓 코드", example = "KRW-BTC")
        String market,
        @Schema(description = "구간 시작(포함, ISO-8601 UTC)", example = "2026-01-01T00:00:00Z")
        Instant from,
        @Schema(description = "구간 끝(제외, ISO-8601 UTC)", example = "2026-01-01T00:10:00Z")
        Instant to,
        @Schema(description = "구간 안의 1분봉. 시간 순(오래된 것부터)이고 중복이 없다. 거래가 없던 분은 빠진다.")
        List<Candle> candles
) {
    // 업비트 한 페이지(200개)로 빠짐없이 조회할 수 있도록 시간 구간 자체를 제한한다.
    public static final Duration MAX_WINDOW = Duration.ofMinutes(200);

    // 마켓 코드를 정규화하고 구간을 검증하며, 캔들이 구간 안에서 중복 없이 시간순이 아니면 IllegalArgumentException을 던진다.
    public CandleRecoveryRange {
        market = MarketCode.normalizeOrThrow(market);
        validateWindow(from, to);
        candles = List.copyOf(Objects.requireNonNull(candles, "candles"));
        Instant previous = null;
        for (Candle candle : candles) {
            if (candle.bucketTime().isBefore(from) || !candle.bucketTime().isBefore(to)
                    || (previous != null && !candle.bucketTime().isAfter(previous))) {
                throw new IllegalArgumentException("복구 캔들은 요청 구간 안에서 중복 없이 시간순이어야 합니다.");
            }
            previous = candle.bucketTime();
        }
    }

    // 완료된 구간의 마켓·시작·끝이 요청한 구간과 정확히 같은지 확인하고, 다르면 IllegalArgumentException을 던진다.
    public void requireRequestedRange(String requestedMarket, Instant requestedFrom, Instant requestedTo) {
        if (!market.equals(requestedMarket) || !from.equals(requestedFrom) || !to.equals(requestedTo)) {
            throw new IllegalArgumentException("백필 완료 구간이 요청 구간과 다릅니다.");
        }
    }

    // 양 끝이 분 경계에 맞고 끝이 시작보다 늦으며 길이가 200분 이하인지 검증하고, 어기면 IllegalArgumentException을 던진다.
    public static void validateWindow(Instant from, Instant to) {
        if (!aligned(from) || !aligned(to) || !to.isAfter(from)
                || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("복구 범위는 분 경계에 맞춘 1~200분 구간이어야 합니다.");
        }
    }

    // 시각이 null이 아니고 정확히 분 경계(초·나노초 0)인지 확인한다.
    private static boolean aligned(Instant value) {
        return value != null && value.getNano() == 0 && Math.floorMod(value.getEpochSecond(), 60) == 0;
    }

    // 구간에 담기는 확정 1분봉 하나. 생성 시 분 경계 시각과 OHLCV 일관성을 검증한다.
    @Schema(name = "RecoveryCandle", description = "확정된 1분봉 하나")
    public record Candle(
            @Schema(description = "봉 시작 시각(분 경계, ISO-8601 UTC)", example = "2026-01-01T00:00:00Z")
            Instant bucketTime,
            @Schema(description = "시가(KRW)", example = "143100000")
            BigDecimal open,
            @Schema(description = "고가(KRW)", example = "143500000")
            BigDecimal high,
            @Schema(description = "저가(KRW)", example = "143050000")
            BigDecimal low,
            @Schema(description = "종가(KRW)", example = "143250000")
            BigDecimal close,
            @Schema(description = "거래량(코인 수량)", example = "3.52")
            BigDecimal volume
    ) {
        // 봉 시각이 분 경계이고 OHLC가 양수, 거래량이 0 이상, 저가 ≤ 시가·종가 ≤ 고가인지 검증한다.
        public Candle {
            if (!aligned(bucketTime) || !positive(open) || !positive(high) || !positive(low) || !positive(close)
                    || volume == null || volume.signum() < 0 || low.compareTo(high) > 0
                    || open.compareTo(low) < 0 || open.compareTo(high) > 0
                    || close.compareTo(low) < 0 || close.compareTo(high) > 0) {
                throw new IllegalArgumentException("복구 캔들의 시각/OHLCV가 올바르지 않습니다.");
            }
        }

        private static boolean positive(BigDecimal value) {
            return value != null && value.signum() > 0;
        }
    }
}
