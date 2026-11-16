package com.mockcoin.shared.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class MockcoinApiDocsConfigurationTest {

    @Test
    void 서비스_문서_정의를_springdoc_빈으로_연결한다() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MockcoinApiDocs.class, () -> MockcoinApiDocs.builder("sample-service", 8099)
                    .title("sample API").description("설명").build());
            context.register(MockcoinApiDocsConfiguration.class);
            context.refresh();

            assertThat(context.getBean(OpenAPI.class).getInfo().getTitle()).isEqualTo("sample API");
            assertThat(context.getBean(OperationCustomizer.class)).isInstanceOf(ApiErrorOperationCustomizer.class);
            assertThat(context.getBean(OpenApiCustomizer.class)).isInstanceOf(MockcoinOpenApiCustomizer.class);
        }
    }

    @Test
    void springdoc_출력_형식을_고정한다() {
        SpringDocConfigProperties properties = new SpringDocConfigProperties();

        Object processed = MockcoinApiDocsConfiguration.mockcoinSpringDocDefaults().postProcessAfterInitialization(properties, "springDocConfigProperties");

        assertThat(processed).isSameAs(properties);
        assertThat(properties.getDefaultProducesMediaType()).isEqualTo("application/json");
        assertThat(properties.isOverrideWithGenericResponse()).isFalse();
        assertThat(properties.isWriterWithOrderByKeys()).isTrue();
        assertThat(properties.isWriterWithDefaultPrettyPrinter()).isTrue();
    }

    @Test
    void 다른_빈은_그대로_통과시킨다() {
        BeanPostProcessor postProcessor = MockcoinApiDocsConfiguration.mockcoinSpringDocDefaults();
        Object bean = new Object();

        assertThat(postProcessor.postProcessAfterInitialization(bean, "other")).isSameAs(bean);
    }
}
