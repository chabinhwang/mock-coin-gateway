package com.mockcoin.shared.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// "이 API가 낼 수 있는 에러 코드 목록"을 선언하는 어노테이션임을 표시하는 메타 어노테이션.
// 표시된 어노테이션은 ErrorCode를 구현한 enum 배열을 돌려주는 value()를 가져야 한다.
// 서비스마다 자기 에러 enum을 받는 선언 어노테이션을 이것으로 표시하면 API 문서와 선언 검증 테스트가 함께 읽는다.
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ApiErrorDeclaration {
}
