package com.mockcoin.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 전 서비스가 상속하는 공통 예외 → 응답 변환 계약.
 *
 * <p>모바일 클라이언트는 어떤 서비스가 실패했든 동일한 `{resultType, success, error}` 봉투를
 * 기대하므로, status/errorCode/reason 매핑이 여기서 고정돼야 한다.
 */
class SharedApiExceptionHandlerTest {
    private final SharedApiExceptionHandler handler = new SharedApiExceptionHandler() {
    };

    @Test
    void ApiException은_지정된_status와_errorCode를_그대로_사용한다() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleApiException(new ApiException(409, "ORDER_LOCK_TIMEOUT", "잠시 후 다시 시도해주세요."));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body(response).resultType()).isEqualTo(ApiResponse.ResultType.FAIL);
        assertThat(body(response).success()).isNull();
        assertThat(body(response).error().errorCode()).isEqualTo("ORDER_LOCK_TIMEOUT");
        assertThat(body(response).error().reason()).isEqualTo("잠시 후 다시 시도해주세요.");
    }

    @Test
    void ErrorCode_정의의_status와_코드가_보존된다() {
        for (CommonErrorCode definition : CommonErrorCode.values()) {
            ResponseEntity<ApiResponse<Void>> response = handler.handleApiException(definition.exception());

            assertThat(response.getStatusCode().value()).isEqualTo(definition.status());
            assertThat(body(response).error().errorCode()).isEqualTo(definition.code());
            assertThat(body(response).error().reason()).isEqualTo(definition.reason());
        }
    }

    @Test
    void Validation_실패는_400_INVALID_PARAMETER와_필드_메시지를_반환한다() throws Exception {
        MethodArgumentNotValidException exception =
                validationException("volume", "주문 수량은 0보다 커야 합니다.");

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidationException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).error().errorCode()).isEqualTo("INVALID_PARAMETER");
        assertThat(body(response).error().reason()).isEqualTo("주문 수량은 0보다 커야 합니다.");
    }

    @Test
    void 필드_에러가_없는_Validation_실패는_기본_메시지로_대체한다() {
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(new BeanPropertyBindingResult(new Object(), "target"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidationException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).error().errorCode()).isEqualTo("INVALID_PARAMETER");
        assertThat(body(response).error().reason()).isEqualTo("요청 파라미터가 유효하지 않습니다.");
    }

    @Test
    void 타입_변환_실패는_400_INVALID_PARAMETER와_파라미터_이름을_반환한다() throws Exception {
        MethodArgumentTypeMismatchException exception =
                typeMismatch("not-a-uuid", UUID.class, "X-Mockcoin-Client-Order-Id");

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatchException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).error().errorCode()).isEqualTo("INVALID_PARAMETER");
        assertThat(body(response).error().reason()).isEqualTo("X-Mockcoin-Client-Order-Id 값이 올바르지 않습니다.");
    }

    @Test
    void 타입_변환_실패_응답에_내부_타입명과_원본_입력값이_새지_않는다() throws Exception {
        // 예외 메시지에는 "java.util.UUID"·"Invalid UUID string: <원본값>"이 들어 있다.
        MethodArgumentTypeMismatchException exception =
                typeMismatch("<script>alert(1)</script>", UUID.class, "X-Mockcoin-Client-Order-Id");

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatchException(exception);

        assertThat(body(response).error().reason())
                .doesNotContain("java.util.UUID")
                .doesNotContain("java.lang.String")
                .doesNotContain("<script>");
    }

    @Test
    void 이름을_알_수_없는_타입_변환_실패는_기본_메시지로_대체한다() throws Exception {
        MethodArgumentTypeMismatchException exception = typeMismatch("bad", Integer.class, "");

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatchException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).error().errorCode()).isEqualTo("INVALID_PARAMETER");
        assertThat(body(response).error().reason()).isEqualTo("요청 파라미터가 유효하지 않습니다.");
    }

    @Test
    void 읽을_수_없는_본문은_400_INVALID_PARAMETER로_변환된다() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected end-of-input",
                new IllegalStateException("com.fasterxml.jackson.core.JsonParseException at [Source: (byte[])]"),
                new MockHttpInputMessage(new byte[0])
        );

        ResponseEntity<ApiResponse<Void>> response = handler.handleNotReadableException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).error().errorCode()).isEqualTo("INVALID_PARAMETER");
        assertThat(body(response).error().reason()).isEqualTo("요청 본문을 읽을 수 없습니다.");
        // Jackson 내부 경로·클래스명이 클라이언트로 나가면 안 된다.
        assertThat(body(response).error().reason())
                .doesNotContain("Jackson")
                .doesNotContain("JSON parse error")
                .doesNotContain("Source:");
    }

    @Test
    void 필수_파라미터_누락은_400과_파라미터_이름을_반환한다() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleRequestBindingException(
                new MissingServletRequestParameterException("interval", "String"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).error().errorCode()).isEqualTo("INVALID_PARAMETER");
        assertThat(body(response).error().reason()).isEqualTo("interval 값이 필요합니다.");
    }

    @Test
    void 필수_헤더_누락은_400과_헤더_이름을_반환한다() throws Exception {
        Method method = Holder.class.getDeclaredMethod("handle", OrderRequest.class);
        ResponseEntity<ApiResponse<Void>> response = handler.handleRequestBindingException(
                new MissingRequestHeaderException("X-Mockcoin-User-Id", new MethodParameter(method, 0)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).error().reason()).isEqualTo("X-Mockcoin-User-Id 값이 필요합니다.");
    }

    @Test
    void 지원하지_않는_method는_405와_Allow_헤더를_반환한다() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupportedException(
                new HttpRequestMethodNotSupportedException("PUT", java.util.List.of("GET", "POST")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(response).error().errorCode()).isEqualTo("METHOD_NOT_ALLOWED");
        // RFC 9110: 405 응답은 Allow 헤더를 반드시 포함해야 한다.
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
    }

    @Test
    void 지원_method를_알_수_없으면_Allow_없이_405만_반환한다() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupportedException(
                new HttpRequestMethodNotSupportedException("PUT"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).isEmpty();
    }

    @Test
    void 지원하지_않는_ContentType은_415를_반환한다() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMediaTypeNotSupportedException(
                new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN, java.util.List.of(MediaType.APPLICATION_JSON)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(body(response).error().errorCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void 매핑되지_않은_경로는_404_NOT_FOUND를_반환한다() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleNoResourceFoundException(
                new NoResourceFoundException(HttpMethod.GET, "/favicon.ico", "/favicon.ico"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(response).error().errorCode()).isEqualTo("NOT_FOUND");
        assertThat(body(response).error().reason()).isEqualTo("요청한 리소스를 찾을 수 없습니다.");
    }

    @Test
    void 예상하지_못한_예외는_500_INTERNAL_ERROR로_정형화한다() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnexpectedException(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(response).error().errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(body(response).error().reason()).isEqualTo("서버 내부 오류가 발생했습니다.");
    }

    @Test
    void 예상하지_못한_예외의_내부_정보가_응답에_새지_않는다() {
        // 스택트레이스·SQL·내부 호스트명 등이 클라이언트로 나가면 안 된다.
        RuntimeException leaky = new RuntimeException(
                "could not execute statement; SQL [insert into user_wallets]; jdbc:postgresql://prod-db:5432");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpectedException(leaky);

        assertThat(body(response).error().reason())
                .doesNotContain("SQL")
                .doesNotContain("user_wallets")
                .doesNotContain("prod-db");
    }

    @Test
    void 모든_실패_응답은_success가_null이고_error가_채워진_동일_봉투다() {
        var responses = java.util.List.of(
                handler.handleApiException(CommonErrorCode.INVALID_PARAMETER.exception("x")),
                handler.handleUnexpectedException(new RuntimeException("y"))
        );

        assertThat(responses).allSatisfy(response -> {
            ApiResponse<Void> payload = body(response);
            assertThat(payload.resultType()).isEqualTo(ApiResponse.ResultType.FAIL);
            assertThat(payload.success()).isNull();
            assertThat(payload.error()).isNotNull();
            assertThat(payload.error().errorCode()).isNotBlank();
            assertThat(payload.error().reason()).isNotBlank();
        });
    }

    // ---------------------------------------------------------------- helpers

    private static ApiResponse<Void> body(ResponseEntity<ApiResponse<Void>> response) {
        ApiResponse<Void> payload = response.getBody();
        assertThat(payload).isNotNull();
        return payload;
    }

    private static MethodArgumentTypeMismatchException typeMismatch(Object value, Class<?> requiredType, String name)
            throws Exception {
        Method method = Holder.class.getDeclaredMethod("handle", OrderRequest.class);
        return new MethodArgumentTypeMismatchException(
                value,
                requiredType,
                name,
                new MethodParameter(method, 0),
                new IllegalArgumentException("Invalid value: " + value)
        );
    }

    private static MethodArgumentNotValidException validationException(String field, String message) throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(new OrderRequest(), "orderRequest");
        bindingResult.rejectValue(field, "invalid", message);
        Method method = Holder.class.getDeclaredMethod("handle", OrderRequest.class);
        return new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);
    }

    // BeanPropertyBindingResult.rejectValue는 실제 프로퍼티가 있어야 FieldError를 만든다.
    @SuppressWarnings("unused")
    public static final class OrderRequest {
        private String volume;

        public String getVolume() {
            return volume;
        }

        public void setVolume(String volume) {
            this.volume = volume;
        }
    }

    @SuppressWarnings("unused")
    private static final class Holder {
        private void handle(OrderRequest request) {
        }
    }
}
