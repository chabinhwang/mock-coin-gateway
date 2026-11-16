package com.mockcoin.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 전 서비스가 공유하는 도메인 예외의 status/errorCode 계약.
 *
 * <p>여기서 만든 statusCode가 그대로 HTTP 응답 코드가 되므로, 유효 범위 밖 값이
 * 런타임에 응답 단계까지 흘러가면 컨테이너가 다른 오류를 낸다.
 */
class ApiExceptionTest {

    @Test
    void status_code_errorCode_reason을_그대로_보존한다() {
        ApiException exception = new ApiException(418, "TEAPOT", "찻주전자입니다.");

        assertThat(exception.statusCode()).isEqualTo(418);
        assertThat(exception.errorCode()).isEqualTo("TEAPOT");
        assertThat(exception.getMessage()).isEqualTo("찻주전자입니다.");
    }

    @Test
    void RuntimeException이라_체크드_예외_선언_없이_전파된다() {
        assertThat(new ApiException(400, "X", "y")).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest(name = "status {0}은 허용")
    @ValueSource(ints = {100, 200, 400, 404, 500, 599})
    void 유효한_HTTP_status_범위를_허용한다(int statusCode) {
        assertThatCode(() -> new ApiException(statusCode, "CODE", "reason")).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "status {0}은 거부")
    @ValueSource(ints = {-1, 0, 99, 600, 1000})
    void 범위를_벗어난_HTTP_status는_생성_시점에_거부한다(int statusCode) {
        assertThatThrownBy(() -> new ApiException(statusCode, "CODE", "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 100 and 599");
    }

    @Test
    void ErrorCode_정의로_만든_예외는_정의의_status와_코드를_쓴다() {
        ApiException exception = CommonErrorCode.NOT_FOUND.exception("주문을 찾을 수 없습니다.");

        assertThat(exception.statusCode()).isEqualTo(404);
        assertThat(exception.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(exception.getMessage()).isEqualTo("주문을 찾을 수 없습니다.");
    }

    @Test
    void 사유를_생략하면_정의의_기본_사유를_쓴다() {
        assertThat(CommonErrorCode.UNAUTHORIZED.exception().getMessage()).isEqualTo("인증이 필요합니다.");
    }

    @Test
    void relay는_다운스트림의_status와_코드를_그대로_보존한다() {
        ApiException exception = CommonErrorCode.INTERNAL_ERROR.relay(409, "ORDER_LOCK_TIMEOUT", "사유");

        assertThat(exception.statusCode()).isEqualTo(409);
        assertThat(exception.errorCode()).isEqualTo("ORDER_LOCK_TIMEOUT");
        assertThat(exception.getMessage()).isEqualTo("사유");
    }

    @Test
    void 상태만_전달하는_relay는_정의의_코드와_기본_사유를_쓴다() {
        ApiException exception = CommonErrorCode.INTERNAL_ERROR.relay(503);

        assertThat(exception.statusCode()).isEqualTo(503);
        assertThat(exception.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(exception.getMessage()).isEqualTo(CommonErrorCode.INTERNAL_ERROR.reason());
    }

    @Test
    void 정의_없이_만들_수_없다() {
        assertThatThrownBy(() -> new ApiException((ErrorCode) null, "사유"))
                .isInstanceOf(NullPointerException.class);
    }
}
