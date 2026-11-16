package com.mockcoin.shared.api;

// 모든 서비스가 공유하는 에러 정의 — 공통 예외 핸들러·필터가 내거나 여러 서비스가 같은 의미로 쓰는 에러.
// 서비스 고유의 에러는 각 서비스의 에러 코드 enum에 둔다.
public enum CommonErrorCode implements ErrorCode {
    INVALID_PARAMETER(400, "INVALID_PARAMETER", "요청 파라미터가 유효하지 않습니다.", false,
            "요청 값이 형식·범위 규칙을 어겼을 때. 필수 값 누락, 타입 변환 실패(숫자 자리에 문자 등), 읽을 수 없는 JSON 본문, "
                    + "필드 검증 규칙 위반, 서비스가 직접 검사하는 값 규칙 위반이 모두 이 코드다. `reason`에 문제가 된 값이 적힌다.",
            "`reason`을 보고 요청 값을 고쳐서 보낸다. 같은 요청을 그대로 다시 보내면 같은 에러가 난다."),
    UNAUTHORIZED(401, "UNAUTHORIZED", "인증이 필요합니다.", false,
            "이 API가 요구하는 인증 정보(토큰·헤더)가 없거나 유효하지 않을 때. 필요한 인증 방식은 API의 보안 요구사항에 적혀 있다.",
            "인증 정보를 갖춰 다시 호출한다."),
    INTERNAL_TOKEN_REJECTED(401, "UNAUTHORIZED", "인증이 필요합니다.", false,
            "서비스 간 내부 토큰 헤더가 없거나, 값이 이 서비스에 설정된 토큰과 다를 때.",
            "호출하는 서비스에 설정된 이 서비스용 내부 토큰 값을 확인한다. 값이 맞기 전에는 재시도해도 결과가 같다."),
    INTERNAL_TOKEN_NOT_CONFIGURED(401, "UNAUTHORIZED", "internal token is not configured", false,
            "이 서비스에 내부 토큰 값이 설정되지 않았을 때. 미설정이면 헤더와 관계없이 모든 내부 요청을 거부한다(fail-closed). "
                    + "`reason`은 서비스마다 다른 영문 문구다.",
            "배포 설정 문제다. 이 서비스의 내부 토큰 환경변수를 설정한 뒤 다시 호출한다."),
    FORBIDDEN(403, "FORBIDDEN", "접근 권한이 없습니다.", false,
            "인증은 됐지만 이 API를 쓸 권한이 없을 때.",
            "권한이 있는 계정으로 호출한다."),
    NOT_FOUND(404, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", false,
            "요청한 대상이 없을 때.",
            "경로와 식별자를 확인한다. 같은 요청을 다시 보내도 결과가 같다."),
    METHOD_NOT_ALLOWED(405, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.", false,
            "경로는 있지만 그 HTTP method를 지원하지 않을 때. 응답 `Allow` 헤더에 지원 method가 담긴다.",
            "`Allow` 헤더의 method로 다시 호출한다."),
    UNSUPPORTED_MEDIA_TYPE(415, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type입니다.", false,
            "요청 본문의 `Content-Type`을 처리할 수 없을 때.",
            "`Content-Type: application/json`으로 다시 보낸다."),
    RATE_LIMIT_EXCEEDED(429, "RATE_LIMIT_EXCEEDED", "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요.", true,
            "호출자(IP·인증 주체 등) 단위 요청 한도를 넘었을 때.",
            "잠시 기다렸다가 다시 보낸다. 응답에 `Retry-After` 헤더가 있으면 그 초만큼 기다린다."),
    INTERNAL_ERROR(500, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.", true,
            "처리 중 예상하지 못한 예외가 났을 때. 상세 원인은 응답에 담지 않고 서버 로그에만 남긴다.",
            "잠시 후 다시 시도한다. 쓰기 API라면 다시 보내기 전에 조회 API로 반영 여부를 먼저 확인한다. 계속 나면 서버 로그를 확인한다.");

    private final int status;
    private final String code;
    private final String reason;
    private final boolean retryable;
    private final String when;
    private final String action;

    CommonErrorCode(int status, String code, String reason, boolean retryable, String when, String action) {
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
