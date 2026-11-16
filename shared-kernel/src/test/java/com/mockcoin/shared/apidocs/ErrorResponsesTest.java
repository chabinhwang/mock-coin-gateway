package com.mockcoin.shared.apidocs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.mockcoin.shared.api.CommonErrorCode;
import com.mockcoin.shared.api.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

class ErrorResponsesTest {
    private final ErrorResponses errorResponses = new ErrorResponses();

    @Test
    void 상태_코드별_응답에_에러_코드별_예시를_만든다() {
        Operation operation = new Operation();

        errorResponses.add(operation, DocumentedError.of(CommonErrorCode.NOT_FOUND));
        errorResponses.add(operation, new DocumentedError(CommonErrorCode.UNAUTHORIZED, "토큰이 만료되었습니다."));

        MediaType notFound = operation.getResponses().get("404").getContent().get(ErrorResponses.JSON);
        assertThat(notFound.getSchema().get$ref()).isEqualTo("#/components/schemas/" + ErrorResponses.FAIL_SCHEMA);
        Example example = notFound.getExamples().get("NOT_FOUND");
        assertThat(example.getSummary()).isEqualTo("NOT_FOUND");
        assertThat(example.getDescription()).contains("**발생 조건**", "**대응**", "**재시도** 불가");
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) operation.getResponses().get("401").getContent()
                .get(ErrorResponses.JSON).getExamples().get("UNAUTHORIZED").getValue();
        assertThat(value).containsEntry("resultType", "FAIL").containsEntry("success", null);
        assertThat(value.get("error")).isEqualTo(Map.of("errorCode", "UNAUTHORIZED", "reason", "토큰이 만료되었습니다."));
    }

    @Test
    void 이름이_코드와_다르면_요약에_둘_다_쓰고_같은_예시는_한_번만_넣는다() {
        Operation operation = new Operation().responses(new ApiResponses());

        errorResponses.add(operation, DocumentedError.of(CommonErrorCode.INTERNAL_TOKEN_REJECTED));
        errorResponses.add(operation, new DocumentedError(CommonErrorCode.INTERNAL_TOKEN_REJECTED, "다른 사유"));

        Map<String, Example> examples = operation.getResponses().get("401").getContent().get(ErrorResponses.JSON).getExamples();
        assertThat(examples).hasSize(1);
        assertThat(examples.get("INTERNAL_TOKEN_REJECTED").getSummary()).isEqualTo("INTERNAL_TOKEN_REJECTED (UNAUTHORIZED)");
    }

    @Test
    void 응답_설명을_에러_코드_목록으로_쓴다() {
        Operation operation = new Operation();
        errorResponses.add(operation, DocumentedError.of(CommonErrorCode.RATE_LIMIT_EXCEEDED));
        errorResponses.add(operation, DocumentedError.of(CommonErrorCode.INTERNAL_TOKEN_NOT_CONFIGURED));
        operation.getResponses().addApiResponse("200", new ApiResponse().description("OK"));
        operation.getResponses().addApiResponse("418", new ApiResponse().description("직접 쓴 응답"));
        operation.getResponses().addApiResponse("599", new ApiResponse().content(new io.swagger.v3.oas.models.media.Content()
                .addMediaType(ErrorResponses.JSON, new MediaType().addExamples("UNKNOWN", new Example()))));

        errorResponses.describe(operation);

        assertThat(operation.getResponses().get("429").getDescription())
                .startsWith("**429 Too Many Requests**")
                .contains("**`RATE_LIMIT_EXCEEDED`**", "(재시도 가능)");
        assertThat(operation.getResponses().get("401").getDescription())
                .contains("**`UNAUTHORIZED`** (`INTERNAL_TOKEN_NOT_CONFIGURED`)", "(재시도 불가)");
        assertThat(operation.getResponses().get("200").getDescription()).isEqualTo("OK");
        assertThat(operation.getResponses().get("418").getDescription()).isEqualTo("직접 쓴 응답");
        assertThat(operation.getResponses().get("599").getDescription()).isNull();
    }

    @Test
    void 응답이_없는_연산은_설명할_것이_없다() {
        Operation operation = new Operation();

        errorResponses.describe(operation);

        assertThat(operation.getResponses()).isNull();
    }

    @Test
    void 이름이_같은_서로_다른_정의는_거부한다() {
        errorResponses.add(new Operation(), DocumentedError.of(CommonErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> errorResponses.add(new Operation(), DocumentedError.of(OtherErrorCode.NOT_FOUND)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("중복");
    }

    @Test
    void 알_수_없는_상태_코드도_설명한다() {
        Operation operation = new Operation();
        errorResponses.add(operation, DocumentedError.of(OtherErrorCode.UNKNOWN_STATUS));

        errorResponses.describe(operation);

        assertThat(operation.getResponses().get("599").getDescription()).startsWith("**599** —");
    }

    enum OtherErrorCode implements ErrorCode {
        NOT_FOUND(404),
        UNKNOWN_STATUS(599);

        private final int status;

        OtherErrorCode(int status) {
            this.status = status;
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public String code() {
            return name();
        }

        @Override
        public String reason() {
            return "사유";
        }

        @Override
        public String when() {
            return "언제";
        }

        @Override
        public String action() {
            return "대응";
        }

        @Override
        public boolean retryable() {
            return false;
        }
    }
}
