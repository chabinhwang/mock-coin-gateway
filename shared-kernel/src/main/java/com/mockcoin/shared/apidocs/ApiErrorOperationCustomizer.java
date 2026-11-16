package com.mockcoin.shared.apidocs;

import com.mockcoin.shared.api.ApiErrorDeclarations;
import com.mockcoin.shared.api.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;

// 컨트롤러 메서드에 선언된 에러 코드(@ApiErrorDeclaration 계열 어노테이션)를 그 API의 에러 응답으로 옮긴다.
// 구현 위치를 x-handler 확장으로 남겨 문서에서 코드로 바로 찾아갈 수 있게 하고, 선언 검증 테스트가 API와 메서드를 잇는 데 쓴다.
final class ApiErrorOperationCustomizer implements OperationCustomizer {
    static final String HANDLER_EXTENSION = "x-handler";

    private final ErrorResponses errorResponses;

    // 에러 응답 등록기를 주입받는다.
    ApiErrorOperationCustomizer(ErrorResponses errorResponses) {
        this.errorResponses = errorResponses;
    }

    // 연산에 구현 메서드 위치(x-handler)를 남기고, 메서드에 선언된 에러 코드를 에러 응답으로 더한다.
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        operation.addExtension(HANDLER_EXTENSION, handlerName(handlerMethod));
        for (ErrorCode definition : ApiErrorDeclarations.of(handlerMethod.getMethod())) {
            errorResponses.add(operation, DocumentedError.of(definition));
        }
        return operation;
    }

    // 프록시를 벗긴 컨트롤러 클래스 이름과 메서드 이름을 '클래스#메서드' 형식으로 만든다.
    static String handlerName(HandlerMethod handlerMethod) {
        return ClassUtils.getUserClass(handlerMethod.getBeanType()).getName() + "#" + handlerMethod.getMethod().getName();
    }
}
