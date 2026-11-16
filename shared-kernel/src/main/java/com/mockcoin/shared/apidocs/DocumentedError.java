package com.mockcoin.shared.apidocs;

import java.util.Objects;

import com.mockcoin.shared.api.ErrorCode;

// API 문서에 실을 에러 하나 — 정의, 예시 응답에 쓸 사유 문구, 실제 응답 상태 코드.
// 필터처럼 예외의 상태 코드와 다른 상태로 응답하는 곳이 있어 상태 코드를 따로 둔다(기본값은 정의의 상태 코드).
record DocumentedError(ErrorCode definition, String exampleReason, int status) {
    // 정의는 필수이며, 예시 사유가 없으면 정의의 기본 사유를 쓴다.
    DocumentedError {
        Objects.requireNonNull(definition, "definition must not be null");
        exampleReason = exampleReason == null ? definition.reason() : exampleReason;
    }

    // 응답 상태 코드를 정의의 상태 코드로 두고 문서 에러를 만든다.
    DocumentedError(ErrorCode definition, String exampleReason) {
        this(definition, exampleReason, definition.status());
    }

    // 정의의 기본 사유와 상태 코드로 문서 에러를 만든다.
    static DocumentedError of(ErrorCode definition) {
        return new DocumentedError(definition, definition.reason());
    }

    // 같은 정의·사유로 실제 응답 상태 코드만 바꾼 문서 에러를 만든다.
    DocumentedError renderedAs(int responseStatus) {
        return new DocumentedError(definition, exampleReason, responseStatus);
    }
}
