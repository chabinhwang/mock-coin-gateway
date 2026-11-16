package com.mockcoin.shared.api;

import io.swagger.v3.oas.annotations.media.Schema;

// API 실패 응답에 포함되는 에러 코드와 사유를 담는 값 객체
@Schema(description = "실패 원인. 클라이언트 분기는 `errorCode`로 하고, `reason`은 사람이 읽는 설명이다.")
public record ErrorResponse(
        @Schema(description = "에러 코드(대문자 스네이크 케이스). API별 가능한 값은 각 에러 응답의 예시를 본다.", example = "INVALID_PARAMETER")
        String errorCode,
        @Schema(description = "사람이 읽는 실패 사유(한국어 또는 영문). 문구는 바뀔 수 있으니 분기 조건으로 쓰지 않는다.", example = "요청 파라미터가 유효하지 않습니다.")
        String reason
) {
}
