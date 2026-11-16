package com.mockcoin.shared.api;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// 서비스 공통 예외 처리 베이스 — ApiException/Validation/Unexpected를 통일된 ApiResponse 실패 포맷으로 변환한다.
// 각 서비스는 @RestControllerAdvice 서브클래스로 상속받고, 서비스별 특화 핸들러만 추가하거나 재정의한다.
public abstract class SharedApiExceptionHandler {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    // 도메인 ApiException을 status/code/message로 정형화해 응답한다
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
        log.warn("API exception status={} code={} reason={}", exception.statusCode(), exception.errorCode(), exception.getMessage());
        return ResponseEntity.status(exception.statusCode())
                .body(ApiResponse.fail(exception.errorCode(), exception.getMessage()));
    }

    // Bean Validation 실패를 INVALID_PARAMETER 400 응답으로 변환한다
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String reason = fieldError == null ? CommonErrorCode.INVALID_PARAMETER.reason() : fieldError.getDefaultMessage();
        log.warn("Validation failed field={} reason={}", fieldError == null ? "<unknown>" : fieldError.getField(), reason);
        return ResponseEntity.badRequest().body(ApiResponse.fail(CommonErrorCode.INVALID_PARAMETER.code(), reason));
    }

    // 헤더/쿼리 파라미터/경로 변수의 타입 변환 실패를 INVALID_PARAMETER 400 응답으로 변환한다.
    // 컨트롤러 진입 전 바인딩 단계에서 터지는 클라이언트 입력 오류라 서버 오류(500)로 다루면 안 된다.
    // 예외 메시지에는 내부 타입명과 클라이언트가 보낸 원본 값이 들어 있으므로 그대로 노출하지 않고
    // 우리가 선언한 파라미터 이름만 사용한다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(MethodArgumentTypeMismatchException exception) {
        String name = exception.getName();
        Class<?> requiredType = exception.getRequiredType();
        log.warn(
                "Type mismatch name={} requiredType={}",
                name == null || name.isBlank() ? "<unknown>" : name,
                requiredType == null ? "<unknown>" : requiredType.getSimpleName()
        );
        String reason = name == null || name.isBlank()
                ? CommonErrorCode.INVALID_PARAMETER.reason()
                : name + " 값이 올바르지 않습니다.";
        return ResponseEntity.badRequest().body(ApiResponse.fail(CommonErrorCode.INVALID_PARAMETER.code(), reason));
    }

    // 요청 본문을 읽을 수 없는 경우(깨진 JSON, 본문 누락, JSON 필드 타입 불일치)를 400으로 변환한다.
    // 예외 메시지에는 Jackson 파싱 경로와 내부 클래스명이 들어 있어 원인 타입만 로깅하고 노출하지 않는다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadableException(HttpMessageNotReadableException exception) {
        log.warn("Malformed request body cause={}", exception.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.badRequest().body(ApiResponse.fail(CommonErrorCode.INVALID_PARAMETER.code(), "요청 본문을 읽을 수 없습니다."));
    }

    // 필수 요청 파라미터/헤더 누락 등 바인딩 실패를 400으로 변환한다.
    // 파라미터 이름은 우리가 선언한 값이라 노출해도 안전하다.
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ApiResponse<Void>> handleRequestBindingException(ServletRequestBindingException exception) {
        String reason = switch (exception) {
            case MissingServletRequestParameterException missing -> missing.getParameterName() + " 값이 필요합니다.";
            case MissingRequestHeaderException missing -> missing.getHeaderName() + " 값이 필요합니다.";
            default -> "필수 요청 값이 누락되었습니다.";
        };
        log.warn("Request binding failed reason={}", reason);
        return ResponseEntity.badRequest().body(ApiResponse.fail(CommonErrorCode.INVALID_PARAMETER.code(), reason));
    }

    // 지원하지 않는 HTTP method를 405로 변환한다. RFC 9110상 405는 Allow 헤더를 반드시 포함해야 한다.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException exception) {
        log.warn("Method not supported method={}", exception.getMethod());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supported = exception.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(new HttpMethod[0]));
        }
        return builder.body(ApiResponse.fail(CommonErrorCode.METHOD_NOT_ALLOWED));
    }

    // 지원하지 않는 Content-Type을 415로 변환한다
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException exception) {
        log.warn("Media type not supported contentType={}", exception.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.fail(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    // 매핑된 핸들러도 정적 리소스도 없는 경로 요청은 서버 오류가 아니라 404로 응답한다.
    // (favicon.ico 같은 요청이 error 로그와 스택트레이스를 남기지 않게 debug로 낮춘다)
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException exception) {
        log.debug("Static resource not found path={}", exception.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(CommonErrorCode.NOT_FOUND));
    }

    // 예기치 못한 예외를 INTERNAL_ERROR 500 응답으로 변환하고 에러 로그를 남긴다
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(CommonErrorCode.INTERNAL_ERROR));
    }
}
