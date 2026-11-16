package com.mockcoin.shared.api;

// API 에러 하나의 정의 — 서비스별 에러 코드 enum이 구현한다.
// 같은 정의가 예외 생성(exception())과 API 문서의 에러 응답(상태 코드·에러 코드·예시·설명)의 원본이 된다.
// 원인별로 상수를 나누면 여러 상수가 같은 code()를 공유할 수 있다(예: 리소스별 NOT_FOUND).
public interface ErrorCode {
    // enum 상수 이름. 문서에서 에러 예시의 이름으로 쓰이므로 한 서비스 안에서 유일해야 한다.
    String name();

    // HTTP 상태 코드
    int status();

    // 응답 본문 error.errorCode 값
    String code();

    // 기본 사유 문구(응답 본문 error.reason). 호출 지점에서 더 구체적인 문구로 바꿔 던질 수 있다.
    String reason();

    // 언제 발생하는지
    String when();

    // 호출하는 쪽이 어떻게 대응하면 되는지
    String action();

    // 같은 요청을 (잠시 후) 다시 보내면 해결될 수 있는지
    boolean retryable();

    // 기본 사유 문구로 예외를 만든다
    default ApiException exception() {
        return new ApiException(this, reason());
    }

    // 호출 지점의 구체적인 사유 문구로 예외를 만든다
    default ApiException exception(String reason) {
        return new ApiException(this, reason);
    }

    // 다운스트림 서비스가 돌려준 status/errorCode/reason을 그대로 전달하는 예외를 만든다.
    // 이 정의는 문서에 "다운스트림 에러 전달"을 설명하는 자리로 쓰이고, 실제 응답 값은 다운스트림 응답을 따른다.
    default ApiException relay(int statusCode, String errorCode, String reason) {
        return new ApiException(statusCode, errorCode, reason);
    }

    // 이 정의의 코드·기본 사유로, HTTP 상태만 다운스트림 응답을 따르는 예외를 만든다
    default ApiException relay(int statusCode) {
        return relay(statusCode, code(), reason());
    }
}
