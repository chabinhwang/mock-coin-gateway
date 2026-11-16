package com.mockcoin.shared.api;

import java.util.Objects;

// HTTP 상태 코드와 에러 코드를 함께 표현하는 API 표준 예외.
// 서비스가 직접 정의하는 에러는 ErrorCode enum의 exception()으로 만든다(문서의 에러 목록과 같은 원본을 쓰기 위해).
public class ApiException extends RuntimeException {
    private final int statusCode;
    private final String errorCode;

    // ErrorCode 정의와 호출 지점의 사유로 API 예외를 생성한다
    public ApiException(ErrorCode definition, String reason) {
        this(Objects.requireNonNull(definition, "definition must not be null").status(), definition.code(), reason);
    }

    // HTTP 상태 코드, 에러 코드, 사유를 받아 API 예외를 생성한다.
    // 다운스트림 응답을 그대로 전달할 때(ErrorCode.relay)와 테스트용이다.
    public ApiException(int statusCode, String errorCode, String reason) {
        super(reason);
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("HTTP status code must be between 100 and 599");
        }
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
