package com.mockcoin.shared.domain;

import com.mockcoin.shared.api.ApiException;
import com.mockcoin.shared.api.CommonErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketCodeTest {

    @Test
    void normalizeOrThrow_uppercasesAndTrims() {
        assertThat(MarketCode.normalizeOrThrow(" krw-btc ")).isEqualTo("KRW-BTC");
    }

    @Test
    void normalizeOrThrow_acceptsBoundedFormat() {
        assertThat(MarketCode.normalizeOrThrow("KRW-BTC")).isEqualTo("KRW-BTC");
        assertThat(MarketCode.normalizeOrThrow("BTC-ETH")).isEqualTo("BTC-ETH");
        assertThat(MarketCode.normalizeOrThrow("krw-a")).isEqualTo("KRW-A");
    }

    @Test
    void normalizeOrThrow_rejectsBlankWithRequiredMessage() {
        assertThatThrownBy(() -> MarketCode.normalizeOrThrow(null))
                .isInstanceOf(ApiException.class)
                .hasMessage(MarketCode.DEFAULT_REQUIRED_MESSAGE);
        assertThatThrownBy(() -> MarketCode.normalizeOrThrow("  "))
                .isInstanceOf(ApiException.class)
                .hasMessage(MarketCode.DEFAULT_REQUIRED_MESSAGE);
    }

    @Test
    void normalizeOrThrow_rejectsTooShortOrTooLongSegments() {
        // base 1자, quote 0자, base 11자, quote 11자는 모두 거부 — bounded 규칙
        assertThatThrownBy(() -> MarketCode.normalizeOrThrow("A-BTC")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> MarketCode.normalizeOrThrow("KRW-")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> MarketCode.normalizeOrThrow("ABCDEFGHIJK-BTC")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> MarketCode.normalizeOrThrow("KRW-ABCDEFGHIJK")).isInstanceOf(ApiException.class);
    }

    @Test
    void normalizeOrThrow_rejectsMalformed() {
        assertThatThrownBy(() -> MarketCode.normalizeOrThrow("KRWBTC")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> MarketCode.normalizeOrThrow("KRW_BTC")).isInstanceOf(ApiException.class);
    }

    @Test
    void normalizeOrThrow_usesCallerErrorCodeAndMessage() {
        assertThatThrownBy(() -> MarketCode.normalizeOrThrow("bad", CommonErrorCode.NOT_FOUND, "required", "invalid"))
                .isInstanceOf(ApiException.class)
                .hasMessage("invalid")
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void normalizeOrNull_returnsNullOnInvalidOrBlank() {
        assertThat(MarketCode.normalizeOrNull(null)).isNull();
        assertThat(MarketCode.normalizeOrNull(" ")).isNull();
        assertThat(MarketCode.normalizeOrNull("nope")).isNull();
        assertThat(MarketCode.normalizeOrNull("krw-btc")).isEqualTo("KRW-BTC");
    }
}
