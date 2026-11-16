package com.mockcoin.shared.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 컨트롤러 메서드가 직접(또는 호출 경로에서) 던지는 공통 에러 코드를 선언한다.
// 인증 실패·요청 형식 오류·서버 오류처럼 모든 API에 공통인 에러는 문서 설정이 일괄로 붙이므로 여기 적지 않는다.
@Documented
@ApiErrorDeclaration
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CommonApiErrors {
    CommonErrorCode[] value();
}
