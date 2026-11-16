package com.mockcoin.gateway.global.controller;

import com.mockcoin.shared.api.ApiException;
import com.mockcoin.shared.api.ApiResponse;
import com.mockcoin.shared.api.SharedApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// gateway-service 전역 예외 처리 — 공통 핸들러 + ApiException에 요청 method/path 로깅 특화
@RestControllerAdvice
public class GlobalApiExceptionHandler extends SharedApiExceptionHandler {

    // ApiException 변환 시 현재 요청의 method/path까지 함께 로깅한다(가용 시)
    @Override
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            String path = request.getQueryString() == null
                    ? request.getRequestURI()
                    : request.getRequestURI() + "?" + request.getQueryString();
            log.warn(
                    "API exception method={} path={} status={} code={} reason={}",
                    request.getMethod(),
                    path,
                    exception.statusCode(),
                    exception.errorCode(),
                    exception.getMessage()
            );
        } else {
            log.warn("API exception status={} code={} reason={}", exception.statusCode(), exception.errorCode(), exception.getMessage());
        }
        return ResponseEntity.status(exception.statusCode())
                .body(ApiResponse.fail(exception.errorCode(), exception.getMessage()));
    }

    // 현재 서블릿 요청을 RequestContextHolder에서 안전하게 조회한다(없으면 null)
    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }
}
