package com.mockcoin.shared.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import com.mockcoin.shared.api.CommonApiErrors;
import com.mockcoin.shared.api.CommonErrorCode;
import io.swagger.v3.oas.models.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

class ApiErrorOperationCustomizerTest {

    @Test
    void 구현_위치와_선언한_에러를_연산에_남긴다() throws Exception {
        HandlerMethod handler = new HandlerMethod(new SampleController(), SampleController.class.getMethod("find"));
        Operation operation = new Operation();

        Operation customized = new ApiErrorOperationCustomizer(new ErrorResponses()).customize(operation, handler);

        assertThat(customized.getExtensions()).containsEntry(ApiErrorOperationCustomizer.HANDLER_EXTENSION,
                SampleController.class.getName() + "#find");
        assertThat(customized.getResponses().get("404").getContent().get(ErrorResponses.JSON).getExamples()).containsKey("NOT_FOUND");
    }

    @Test
    void 선언이_없으면_응답을_만들지_않는다() throws Exception {
        HandlerMethod handler = new HandlerMethod(new SampleController(), SampleController.class.getMethod("list"));

        Operation customized = new ApiErrorOperationCustomizer(new ErrorResponses()).customize(new Operation(), handler);

        assertThat(customized.getResponses()).isNull();
        assertThat(ApiErrorOperationCustomizer.handlerName(handler)).endsWith("SampleController#list");
    }

    static class SampleController {
        @CommonApiErrors(CommonErrorCode.NOT_FOUND)
        public void find() {
        }

        public void list() {
        }
    }
}
