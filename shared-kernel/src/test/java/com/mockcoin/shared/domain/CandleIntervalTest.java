package com.mockcoin.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * 캔들 간격 코드는 모바일(`timeframe-config.js`)·market-service 집계·Upbit 수집이
 * 공유하는 문자열 계약이다. 여기서 오타가 나면 조용히 빈 차트가 된다.
 */
class CandleIntervalTest {

    @Test
    void 간격_코드는_클라이언트와_합의한_문자열이다() {
        assertThat(CandleInterval.ONE_MINUTE.code()).isEqualTo("1m");
        assertThat(CandleInterval.FIVE_MINUTES.code()).isEqualTo("5m");
        assertThat(CandleInterval.FIFTEEN_MINUTES.code()).isEqualTo("15m");
        assertThat(CandleInterval.ONE_HOUR.code()).isEqualTo("1h");
        assertThat(CandleInterval.FOUR_HOURS.code()).isEqualTo("4h");
        assertThat(CandleInterval.ONE_DAY.code()).isEqualTo("1d");
    }

    @Test
    void 지원_간격은_6종이며_순서가_짧은것부터_긴것이다() {
        assertThat(Arrays.stream(CandleInterval.values()).map(CandleInterval::code))
                .containsExactly("1m", "5m", "15m", "1h", "4h", "1d");
    }

    @Test
    void 간격_코드는_서로_중복되지_않는다() {
        assertThat(Arrays.stream(CandleInterval.values()).map(CandleInterval::code).distinct().count())
                .isEqualTo(CandleInterval.values().length);
    }
}
