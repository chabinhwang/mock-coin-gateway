package com.mockcoin.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.mockcoin.shared.domain.CandleRecoveryRange.Candle;
import org.junit.jupiter.api.Test;

/**
 * The recovery range is the contract that lets trading tell an untraded minute from a missing one, so its
 * invariants are what stop a malformed or mismatched backfill from being read as "nothing traded here".
 */
class CandleRecoveryRangeTest {
    private static final Instant FROM = Instant.parse("2026-04-25T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-04-25T00:10:00Z");

    @Test
    void anEmptyCandleListIsAValidConfirmedUntradedRange() {
        CandleRecoveryRange range = new CandleRecoveryRange("krw-btc", FROM, TO, List.of());

        assertThat(range.market()).isEqualTo("KRW-BTC");
        assertThat(range.candles()).isEmpty();
    }

    @Test
    void aCandleOutsideTheRangeIsRejected() {
        assertThatThrownBy(() -> new CandleRecoveryRange("KRW-BTC", FROM, TO, List.of(candle(TO))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandleRecoveryRange("KRW-BTC", FROM, TO, List.of(candle(FROM.minusSeconds(60)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outOfOrderOrDuplicatedCandlesAreRejected() {
        assertThatThrownBy(() -> new CandleRecoveryRange("KRW-BTC", FROM, TO,
                List.of(candle(FROM.plusSeconds(120)), candle(FROM))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandleRecoveryRange("KRW-BTC", FROM, TO, List.of(candle(FROM), candle(FROM))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void windowMustBeMinuteAlignedAndNoWiderThanOneUpbitPage() {
        assertThatCode(() -> CandleRecoveryRange.validateWindow(FROM, FROM.plus(CandleRecoveryRange.MAX_WINDOW)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> CandleRecoveryRange.validateWindow(
                FROM, FROM.plus(CandleRecoveryRange.MAX_WINDOW).plus(Duration.ofMinutes(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CandleRecoveryRange.validateWindow(FROM.plusSeconds(30), TO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CandleRecoveryRange.validateWindow(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRangeAnsweringADifferentRequestIsRejected() {
        // 요청과 다른 구간을 그대로 받아들이면, 정산하지 않은 분까지 정산된 것으로 표시된다.
        CandleRecoveryRange range = new CandleRecoveryRange("KRW-BTC", FROM, TO, List.of());

        assertThatCode(() -> range.requireRequestedRange("KRW-BTC", FROM, TO)).doesNotThrowAnyException();
        assertThatThrownBy(() -> range.requireRequestedRange("KRW-ETH", FROM, TO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> range.requireRequestedRange("KRW-BTC", FROM, TO.minusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCandleWhoseOhlcContradictsItselfIsRejected() {
        assertThatThrownBy(() -> new Candle(FROM, dec("100"), dec("90"), dec("110"), dec("100"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        // 종가가 저가~고가 밖이면 그 봉으로 어떤 체결 판단도 신뢰할 수 없다.
        assertThatThrownBy(() -> new Candle(FROM, dec("100"), dec("110"), dec("90"), dec("120"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Candle(FROM, dec("100"), dec("110"), dec("90"), dec("100"), dec("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Candle(FROM.plusSeconds(30), dec("100"), dec("110"), dec("90"), dec("100"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Candle candle(Instant bucketTime) {
        return new Candle(bucketTime, dec("100"), dec("110"), dec("90"), dec("100"), BigDecimal.ONE);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }
}
