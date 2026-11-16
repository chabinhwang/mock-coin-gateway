package com.mockcoin.shared.api;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

// 메서드에 붙은 에러 선언 어노테이션(@ApiErrorDeclaration으로 표시된 것)을 모두 읽는다.
public final class ApiErrorDeclarations {
    private ApiErrorDeclarations() {
    }

    // 선언 순서대로 에러 정의를 돌려준다. 선언이 없으면 빈 목록이다.
    public static List<ErrorCode> of(Method method) {
        List<ErrorCode> declared = new ArrayList<>();
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(ApiErrorDeclaration.class)) {
                declared.addAll(List.of(values(annotation)));
            }
        }
        return List.copyOf(declared);
    }

    // 에러 선언 어노테이션의 value()를 리플렉션으로 읽고, ErrorCode 배열이 아니거나 읽지 못하면 IllegalStateException을 던진다.
    private static ErrorCode[] values(Annotation annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        try {
            Method value = type.getMethod("value");
            if (!value.getReturnType().isArray()
                    || !ErrorCode.class.isAssignableFrom(value.getReturnType().getComponentType())) {
                throw new IllegalStateException(type.getName() + ".value()는 ErrorCode 배열이어야 합니다.");
            }
            return (ErrorCode[]) value.invoke(annotation);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(type.getName() + "에 value()가 없습니다.", exception);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException(type.getName() + ".value()를 읽지 못했습니다.", exception);
        }
    }
}
