package com.mockcoin.gateway.global.error;

import com.mockcoin.shared.api.ErrorCode;

// gateway-service가 직접 내는 에러 정의. 예외 생성과 API 문서의 에러 응답이 모두 이 enum을 원본으로 쓴다.
// 공통 에러(INVALID_PARAMETER, RATE_LIMIT_EXCEEDED 등)는 CommonErrorCode를 쓴다. 하위 서비스가 낸 에러는 하위 서비스 문서를 본다.
public enum GatewayErrorCode implements ErrorCode {
    ACCESS_TOKEN_REJECTED(401, "UNAUTHORIZED", "인증이 필요합니다.", false,
            "`Authorization` 헤더가 없거나, user-service가 access token을 거절했거나(401·403), 로그아웃 등으로 폐기된 토큰일 때.",
            "로그인하거나 토큰을 갱신(`POST /api/v1/auth/refresh`)한 뒤 다시 보낸다."),
    AUTH_SERVICE_UNAVAILABLE(503, "AUTH_SERVICE_UNAVAILABLE", "인증 서비스를 일시적으로 사용할 수 없습니다.", true,
            "토큰을 확인하려고 user-service `GET /api/v1/auth/me`를 불렀는데 연결에 실패했거나 401·403이 아닌 실패로 답했을 때.",
            "잠시 후 다시 보낸다."),
    AUTH_SERVICE_INVALID_RESPONSE(502, "AUTH_SERVICE_INVALID_RESPONSE", "인증 서비스 응답이 올바르지 않습니다.", true,
            "user-service 토큰 확인 응답이 비었거나 `success.userId`가 없을 때.",
            "잠시 후 다시 보낸다. 계속 나면 user-service 응답 형식을 확인한다."),
    ADMIN_AUTH_REQUIRED(401, "UNAUTHORIZED", "인증이 필요합니다.", false,
            "관리자 토큰 헤더 값이 틀렸거나 없고, 유효한 관리자 세션 쿠키도 없을 때.",
            "관리자 로그인으로 세션 쿠키를 받거나 올바른 `X-Mockcoin-Admin-Token`을 보낸다."),
    ADMIN_TOKEN_NOT_CONFIGURED(401, "UNAUTHORIZED", "gateway admin token is not configured", false,
            "관리자 세션이 없고, 게이트웨이에 관리자 토큰(`GATEWAY_ADMIN_INTERNAL_TOKEN`)도 설정되지 않았을 때.",
            "관리자 로그인으로 세션 쿠키를 받는다. 토큰 호출이 필요하면 배포 설정에 관리자 토큰을 넣는다."),
    ADMIN_ORIGIN_REJECTED(401, "UNAUTHORIZED", "admin origin validation failed", false,
            "세션 쿠키로 인증하는 변경 요청(POST·PUT 등)에 `Origin`(없으면 `Referer`)이 없거나 게이트웨이와 다른 출처일 때(CSRF 방어). 토큰 헤더로 부르면 검사하지 않는다.",
            "관리자 콘솔 화면(같은 출처)에서 보내거나 `X-Mockcoin-Admin-Token` 헤더로 부른다."),
    ADMIN_SESSION_REQUIRED(401, "UNAUTHORIZED", "관리자 세션이 필요합니다.", false,
            "이미 패스키가 등록돼 있는데 관리자 세션 없이 패스키를 추가 등록하려 할 때.",
            "패스키로 로그인한 뒤 다시 등록한다."),
    REGISTRATION_SESSION_REQUIRED(401, "UNAUTHORIZED", "인증이 필요합니다.", false,
            "패스키 등록 세션(비밀번호 로그인으로 받은 세션)이나 관리자 세션 없이 패스키 등록을 시작·완료하려 할 때.",
            "먼저 비밀번호 로그인(`/api/auth/login`)을 한다."),
    ACCOUNT_LOCKED(423, "ACCOUNT_LOCKED", "계정이 잠겼습니다. 잠시 후 다시 시도해주세요.", true,
            "같은 IP에서 로그인 실패가 5번 쌓여 30분 동안 잠겼을 때.",
            "잠금이 풀린 뒤(최대 30분) 다시 시도한다."),
    PASSKEY_REQUIRED(403, "PASSKEY_REQUIRED", "패스키가 등록되어 있습니다. 패스키로 로그인해주세요.", false,
            "패스키 사용이 켜져 있고 이미 패스키가 등록된 상태에서 비밀번호로 로그인하려 할 때.",
            "패스키 로그인(`/api/auth/webauthn/login/*`)을 쓴다."),
    INVALID_CREDENTIALS(401, "INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다.", false,
            "아이디·비밀번호가 틀렸거나 서버에 관리자 계정이 설정되지 않았을 때. 실패는 IP별로 세어 5번이면 잠긴다.",
            "아이디·비밀번호를 확인한다."),
    PASSKEY_DISABLED(403, "PASSKEY_DISABLED", "패스키 로그인이 비활성화되어 있습니다.", false,
            "패스키 사용(`mockcoin.admin.passkey-enabled`)이 꺼진 환경에서 패스키 API를 부를 때.",
            "비밀번호 로그인을 쓴다."),
    NO_PASSKEY(400, "NO_PASSKEY", "등록된 패스키가 없습니다.", false,
            "등록된 패스키가 하나도 없는데 패스키 로그인을 시작하려 할 때.",
            "비밀번호로 로그인해 패스키를 먼저 등록한다."),
    WEBAUTHN_REGISTRATION_FAILED(400, "WEBAUTHN_REGISTRATION_FAILED", "패스키 등록에 실패했습니다.", false,
            "브라우저가 보낸 패스키 등록 응답을 검증하지 못했을 때(만료·없는 challenge 포함).",
            "등록을 처음(`register/options`)부터 다시 한다."),
    WEBAUTHN_LOGIN_FAILED(401, "WEBAUTHN_LOGIN_FAILED", "패스키 인증에 실패했습니다.", false,
            "브라우저가 보낸 패스키 로그인 응답을 검증하지 못했을 때(만료·없는 challenge 포함).",
            "로그인을 처음(`login/options`)부터 다시 한다."),
    DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED(401, "UNAUTHORIZED", "downstream admin token is not configured", false,
            "게이트웨이에 호출할 하위 서비스용 내부 토큰(`mockcoin.admin.*-service-token`)이 설정되지 않았을 때.",
            "배포 설정 문제다. 해당 하위 서비스 토큰을 설정한다."),
    DOWNSTREAM_ADMIN_UNAVAILABLE(502, "DOWNSTREAM_ADMIN_UNAVAILABLE", "관리 API 서비스에 연결할 수 없습니다.", true,
            "하위 서비스에 연결하지 못했거나 응답 시간이 지났을 때.",
            "잠시 후 다시 시도한다. 변경 요청이면 먼저 조회로 반영 여부를 확인한다."),
    DOWNSTREAM_ADMIN_FAILED(502, "DOWNSTREAM_ADMIN_FAILED", "관리 API가 JSON이 아닌 응답을 반환했습니다.", true,
            "하위 서비스 응답을 해석할 수 없을 때: 성공 응답이 HTML이거나 성공 봉투가 아님(502, `reason`='<서비스> 관리 API 응답이 올바르지 않습니다.' 등), "
                    + "또는 실패 응답 본문이 HTML·빈 본문임(이때 HTTP 상태는 하위 서비스 응답 상태, `reason`='관리 API가 비어 있는 실패 응답을 반환했습니다.' 등).",
            "잠시 후 다시 시도한다. 계속 나면 하위 서비스 상태와 경로를 확인한다."),
    DOWNSTREAM_ADMIN_ERROR(502, "DOWNSTREAM_ADMIN_FAILED", "user-service 관리 API 호출에 실패했습니다.", false,
            "여러 서비스 데이터를 합치는 조회에서 하위 서비스가 실패로 답했을 때. HTTP 상태·`errorCode`·`reason`은 하위 서비스 실패 응답 그대로다"
                    + "(실패 본문에 `errorCode`가 없으면 `DOWNSTREAM_ADMIN_FAILED`, `reason`='<서비스> 관리 API 호출에 실패했습니다.').",
            "`errorCode`에 맞게 처리한다. 의미는 하위 서비스 API 문서를 본다."),
    DOWNSTREAM_ADMIN_OVERVIEW_FAILED(502, "DOWNSTREAM_ADMIN_OVERVIEW_FAILED", "user-service overview 호출에 실패했습니다.", true,
            "대시보드에 꼭 필요한 user-service 현황 조회가 실패했거나(실패 상태로 답함, 연결 실패, 성공 봉투가 아님) 중단됐을 때. `reason`에 원인이 적힌다.",
            "잠시 후 다시 시도한다.");

    private final int status;
    private final String code;
    private final String reason;
    private final boolean retryable;
    private final String when;
    private final String action;

    GatewayErrorCode(int status, String code, String reason, boolean retryable, String when, String action) {
        this.status = status;
        this.code = code;
        this.reason = reason;
        this.retryable = retryable;
        this.when = when;
        this.action = action;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public String when() {
        return when;
    }

    @Override
    public String action() {
        return action;
    }

    @Override
    public boolean retryable() {
        return retryable;
    }
}
