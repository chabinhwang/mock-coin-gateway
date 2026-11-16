package com.mockcoin.gateway.global.error;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.mockcoin.shared.api.ApiErrorDeclaration;

// 컨트롤러 메서드가 낼 수 있는 gateway-service 에러 코드를 선언한다. API 문서의 에러 응답이 이 선언에서 만들어진다.
@Documented
@ApiErrorDeclaration
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GatewayApiErrors {
    GatewayErrorCode[] value();
}
