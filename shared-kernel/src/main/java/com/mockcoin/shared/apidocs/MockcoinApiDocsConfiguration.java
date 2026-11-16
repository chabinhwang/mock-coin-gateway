package com.mockcoin.shared.apidocs;

import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 서비스가 등록한 MockcoinApiDocs를 springdoc에 연결한다. 서비스 문서 설정 클래스가 @Import로 가져온다.
@Configuration(proxyBeanMethods = false)
public class MockcoinApiDocsConfiguration {

    // 명세 출력 형식을 모든 서비스에서 같게 고정한다(키 정렬·들여쓰기는 커밋된 명세 파일 비교가 안정적이도록).
    @Bean
    static BeanPostProcessor mockcoinSpringDocDefaults() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SpringDocConfigProperties properties) {
                    applyDefaults(properties);
                }
                return bean;
            }
        };
    }

    // 기본 응답 타입을 JSON으로 두고, 예외 핸들러 응답 자동 부착을 끄고, 키 정렬·들여쓰기 출력을 켠다.
    static void applyDefaults(SpringDocConfigProperties properties) {
        properties.setDefaultProducesMediaType("application/json");
        // @RestControllerAdvice 예외 핸들러 응답을 모든 API에 붙이지 않는다. 에러 응답은 ErrorCode 선언에서 만든다.
        properties.setOverrideWithGenericResponse(false);
        properties.setWriterWithOrderByKeys(true);
        properties.setWriterWithDefaultPrettyPrinter(true);
    }

    // 두 커스터마이저가 함께 쓰는 에러 응답 등록기(예시 이름 중복 검사 포함)를 등록한다.
    @Bean
    ErrorResponses mockcoinErrorResponses() {
        return new ErrorResponses();
    }

    // 서비스 문서 정의의 기본 OpenAPI(정보·로컬 서버·보안 스킴)를 명세의 시작점으로 등록한다.
    @Bean
    OpenAPI mockcoinOpenApi(MockcoinApiDocs docs) {
        return docs.baseOpenApi();
    }

    // 컨트롤러 메서드의 에러 선언을 각 API 에러 응답으로 옮기는 커스터마이저를 등록한다.
    @Bean
    OperationCustomizer mockcoinApiErrorOperationCustomizer(ErrorResponses errorResponses) {
        return new ApiErrorOperationCustomizer(errorResponses);
    }

    // 모든 API에 공통 문서 규칙(보안·공통 에러·성공 예시·태그 묶음)을 적용하는 커스터마이저를 등록한다.
    @Bean
    OpenApiCustomizer mockcoinOpenApiCustomizer(MockcoinApiDocs docs, ErrorResponses errorResponses) {
        return new MockcoinOpenApiCustomizer(docs, errorResponses);
    }
}
