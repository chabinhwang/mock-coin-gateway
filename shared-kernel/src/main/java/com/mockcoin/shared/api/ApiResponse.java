package com.mockcoin.shared.api;

import io.swagger.v3.oas.annotations.media.Schema;

// API 표준 응답 봉투(성공 페이로드 또는 에러 정보를 담는다)
@Schema(description = "모든 REST 응답을 감싸는 공통 봉투. 성공이면 `resultType=SUCCESS`와 `success`, 실패면 `resultType=FAIL`과 `error`가 채워지고 나머지 하나는 `null`이다.")
public record ApiResponse<T>(
        @Schema(description = "처리 결과. `SUCCESS`=성공(결과는 `success`), `FAIL`=실패(원인은 `error`).", example = "SUCCESS")
        ResultType resultType,
        @Schema(description = "성공 결과. 실패 응답이거나 결과가 없는 API면 `null`이다.")
        T success,
        @Schema(description = "실패 정보. 성공 응답이면 `null`이다.")
        ErrorResponse error
) {
    // 성공 응답을 생성한다
    public static <T> ApiResponse<T> success(T payload) {
        return new ApiResponse<>(ResultType.SUCCESS, payload, null);
    }

    // success의 별칭으로 성공 응답을 생성한다
    public static <T> ApiResponse<T> ok(T data) {
        return success(data);
    }

    // 실패 응답을 에러 코드와 사유로 생성한다
    public static <T> ApiResponse<T> fail(String errorCode, String reason) {
        return new ApiResponse<>(ResultType.FAIL, null, new ErrorResponse(errorCode, reason));
    }

    // 실패 응답을 에러 정의의 코드와 기본 사유로 생성한다
    public static <T> ApiResponse<T> fail(ErrorCode definition) {
        return fail(definition.code(), definition.reason());
    }

    // 응답 결과 타입(성공/실패) 구분 enum
    public enum ResultType {
        SUCCESS,
        FAIL
    }
}
