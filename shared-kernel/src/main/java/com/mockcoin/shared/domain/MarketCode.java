package com.mockcoin.shared.domain;

import com.mockcoin.shared.api.CommonErrorCode;
import com.mockcoin.shared.api.ErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

// 마켓 코드 정규화·검증 단일 출처. 서비스마다 흩어져 있던 정규식/검증 로직을 통일한다.
// 정규식은 단일 규칙(base 2~10자, quote 1~10자)으로 고정하여 서비스 간 수용 범위 불일치를 제거한다.
// quote 1자는 Upbit KRW-A(볼타) 같은 한 글자 심볼 때문이다.
public final class MarketCode {
    public static final String DEFAULT_REQUIRED_MESSAGE = "마켓 코드는 필수입니다.";
    public static final String DEFAULT_INVALID_MESSAGE = "올바르지 않은 마켓 코드입니다.";

    private static final Pattern PATTERN = Pattern.compile("^[A-Z]{2,10}-[A-Z0-9]{1,10}$");

    private MarketCode() {
    }

    // 기본 코드(INVALID_PARAMETER)/메시지로 정규화·검증한다. 실패 시 ApiException(400).
    public static String normalizeOrThrow(String market) {
        return normalizeOrThrow(market, CommonErrorCode.INVALID_PARAMETER, DEFAULT_REQUIRED_MESSAGE, DEFAULT_INVALID_MESSAGE);
    }

    // 호출자가 에러 정의/메시지를 지정해 정규화·검증한다. 실패 시 지정한 에러의 ApiException.
    public static String normalizeOrThrow(String market, ErrorCode error, String requiredMessage, String invalidMessage) {
        if (market == null || market.isBlank()) {
            throw error.exception(requiredMessage);
        }
        String normalized = market.strip().toUpperCase(Locale.ROOT);
        if (!PATTERN.matcher(normalized).matches()) {
            throw error.exception(invalidMessage);
        }
        return normalized;
    }

    // 정규화·검증에 실패하면 null을 반환한다(예외를 던지지 않는 호출자용).
    public static String normalizeOrNull(String market) {
        if (market == null || market.isBlank()) {
            return null;
        }
        String normalized = market.strip().toUpperCase(Locale.ROOT);
        return PATTERN.matcher(normalized).matches() ? normalized : null;
    }
}
