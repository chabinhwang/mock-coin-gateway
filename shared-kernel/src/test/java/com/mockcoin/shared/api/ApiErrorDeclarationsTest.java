package com.mockcoin.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class ApiErrorDeclarationsTest {

    @Test
    void 선언_어노테이션의_에러를_선언_순서대로_읽는다() throws Exception {
        assertThat(ApiErrorDeclarations.of(method("declared")))
                .containsExactly(CommonErrorCode.NOT_FOUND, CommonErrorCode.RATE_LIMIT_EXCEEDED);
    }

    @Test
    void 선언이_없으면_빈_목록이다() throws Exception {
        assertThat(ApiErrorDeclarations.of(method("undeclared"))).isEmpty();
    }

    @Test
    void value가_ErrorCode_배열이_아니면_거부한다() throws Exception {
        assertThatThrownBy(() -> ApiErrorDeclarations.of(method("wrongType")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ErrorCode 배열");
    }

    @Test
    void value가_없으면_거부한다() throws Exception {
        assertThatThrownBy(() -> ApiErrorDeclarations.of(method("noValue")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("value()가 없습니다");
    }

    private static Method method(String name) throws NoSuchMethodException {
        return Fixture.class.getDeclaredMethod(name);
    }

    @ApiErrorDeclaration
    @Retention(RetentionPolicy.RUNTIME)
    @interface WrongType {
        String[] value();
    }

    @ApiErrorDeclaration
    @Retention(RetentionPolicy.RUNTIME)
    @interface NoValue {
    }

    static class Fixture {
        @CommonApiErrors({CommonErrorCode.NOT_FOUND, CommonErrorCode.RATE_LIMIT_EXCEEDED})
        void declared() {
        }

        void undeclared() {
        }

        @WrongType("X")
        void wrongType() {
        }

        @NoValue
        void noValue() {
        }
    }
}
