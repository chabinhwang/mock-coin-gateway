package com.mockcoin.shared.domain;

// 차트/캔들 집계에 사용하는 시간 간격(1m, 5m, 15m, 1h, 4h, 1d) enum
public enum CandleInterval {
    ONE_MINUTE("1m"),
    FIVE_MINUTES("5m"),
    FIFTEEN_MINUTES("15m"),
    ONE_HOUR("1h"),
    FOUR_HOURS("4h"),
    ONE_DAY("1d");

    private final String code;

    CandleInterval(String code) {
        this.code = code;
    }

    // 외부 표현용 간격 코드 문자열(예: "1m")을 반환한다
    public String code() {
        return code;
    }
}
