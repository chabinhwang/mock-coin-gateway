package com.mockcoin.shared.apidocs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mockcoin.shared.api.CommonErrorCode;
import com.mockcoin.shared.security.InternalTokenAuthenticationFilter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class MockcoinApiDocsTest {

    @Test
    void 기본_문서에_정보_로컬_서버_보안_스킴을_담는다() {
        MockcoinApiDocs docs = MockcoinApiDocs.builder("sample-service", 8099)
                .title("sample API")
                .description("설명")
                .bearerToken("토큰", 401, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.INVALID_PARAMETER)
                .headerToken("X-Header", "헤더")
                .cookieSession("SESSION", "쿠키")
                .build();

        OpenAPI openApi = docs.baseOpenApi();

        assertThat(docs.serviceName()).isEqualTo("sample-service");
        assertThat(openApi.getInfo().getTitle()).isEqualTo("sample API");
        assertThat(openApi.getInfo().getDescription()).isEqualTo("설명");
        assertThat(openApi.getServers().getFirst().getUrl()).isEqualTo("http://localhost:8099");
        assertThat(openApi.getComponents().getSecuritySchemes()).containsOnlyKeys(MockcoinApiDocs.BEARER_AUTH, "X-Header", "SESSION");
        assertThat(openApi.getComponents().getSecuritySchemes().get(MockcoinApiDocs.BEARER_AUTH).getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(openApi.getComponents().getSecuritySchemes().get("SESSION").getIn()).isEqualTo(SecurityScheme.In.COOKIE);
        assertThat(docs.schemes().get(MockcoinApiDocs.BEARER_AUTH).failures())
                .extracting(DocumentedError::definition, DocumentedError::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(CommonErrorCode.UNAUTHORIZED, 401),
                        org.assertj.core.groups.Tuple.tuple(CommonErrorCode.INVALID_PARAMETER, 401));
    }

    @Test
    void 상태를_지정하지_않은_bearer_스킴은_정의의_상태를_그대로_쓴다() {
        MockcoinApiDocs docs = MockcoinApiDocs.builder("sample-service", 8099)
                .title("t").description("d")
                .bearerToken("토큰", CommonErrorCode.UNAUTHORIZED, CommonErrorCode.INTERNAL_ERROR)
                .build();

        assertThat(docs.schemes().get(MockcoinApiDocs.BEARER_AUTH).failures())
                .extracting(DocumentedError::definition, DocumentedError::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(CommonErrorCode.UNAUTHORIZED, 401),
                        org.assertj.core.groups.Tuple.tuple(CommonErrorCode.INTERNAL_ERROR, 500));
    }

    @Test
    void 내부_토큰_필터의_헤더와_URL_패턴과_미설정_사유를_읽는다() {
        FilterRegistrationBean<InternalTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(
                new InternalTokenAuthenticationFilter("X-Internal", () -> "t", "sample internal token is not configured"));
        registration.addUrlPatterns("/internal/sample/*");

        MockcoinApiDocs docs = MockcoinApiDocs.builder("sample-service", 8099)
                .title("t").description("d")
                .internalTokenFilter(registration, "내부 토큰")
                .errorsForPaths(path -> path.startsWith("/api/"), CommonErrorCode.RATE_LIMIT_EXCEEDED)
                .build();

        MockcoinApiDocs.SchemeDefinition scheme = docs.schemes().get("X-Internal");
        assertThat(scheme.urlPatterns()).containsExactly("/internal/sample/*");
        assertThat(scheme.failures()).extracting(DocumentedError::exampleReason)
                .containsExactly("인증이 필요합니다.", "sample internal token is not configured");
        assertThat(docs.pathErrorRules()).hasSize(1);
    }

    @Test
    void URL_패턴이_없는_내부_토큰_필터는_거부한다() {
        FilterRegistrationBean<InternalTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(
                new InternalTokenAuthenticationFilter("X-Internal", () -> "t", "reason"));

        assertThatThrownBy(() -> MockcoinApiDocs.builder("s", 1).internalTokenFilter(registration, "d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL 패턴");
    }

    @Test
    void 같은_이름의_보안_스킴은_거부한다() {
        assertThatThrownBy(() -> MockcoinApiDocs.builder("s", 1)
                .headerToken("X-Header", "a")
                .headerToken("X-Header", "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }

    @Test
    void 제목과_설명은_필수다() {
        assertThatThrownBy(() -> MockcoinApiDocs.builder("s", 1).description("d").build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MockcoinApiDocs.builder("s", 1).title("t").build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 예시_사유를_생략하면_정의의_기본_사유를_쓴다() {
        assertThat(new DocumentedError(CommonErrorCode.NOT_FOUND, null).exampleReason())
                .isEqualTo(CommonErrorCode.NOT_FOUND.reason());
    }
}
