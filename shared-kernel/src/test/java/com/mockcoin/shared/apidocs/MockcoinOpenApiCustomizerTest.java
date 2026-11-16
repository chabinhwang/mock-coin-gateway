package com.mockcoin.shared.apidocs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.mockcoin.shared.api.CommonErrorCode;
import com.mockcoin.shared.security.InternalTokenAuthenticationFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class MockcoinOpenApiCustomizerTest {

    @Test
    void 경로로_보호하는_스킴과_공통_에러와_성공_예시를_붙인다() {
        OpenAPI openApi = openApi();
        Operation internal = operation("내부: 관리");
        Operation getItem = operation("항목").addParametersItem(new Parameter().in("path").name("id").schema(new Schema<>().type("integer")))
                .addSecurityItem(new SecurityRequirement().addList(MockcoinApiDocs.BEARER_AUTH));
        getItem.getResponses().addApiResponse("200", new ApiResponse().description("OK")
                .content(new Content().addMediaType(ErrorResponses.JSON, new MediaType().schema(new Schema<>().$ref("ApiResponseItem")))));
        Operation create = operation("항목").requestBody(new RequestBody());
        Operation plain = operation("항목").addParametersItem(new Parameter().in("query").name("q").schema(new Schema<>().type("string")));
        plain.getResponses().addApiResponse("200", new ApiResponse()
                .content(new Content().addMediaType(ErrorResponses.JSON, new MediaType().schema(new Schema<>().type("string")))));
        Operation console = operation("콘솔");
        openApi.paths(new Paths()
                .addPathItem("/internal/sample/admin/users", new PathItem().get(internal))
                .addPathItem("/api/v1/items/{id}", new PathItem().get(getItem))
                .addPathItem("/api/v1/items", new PathItem().post(create))
                .addPathItem("/api/v1/plain", new PathItem().get(plain))
                .addPathItem("/admin/api/console", new PathItem().get(console)));
        openApi.tags(List.of(new Tag().name("항목"), new Tag().name("내부: 관리"), new Tag().name("콘솔"), new Tag().name("미사용")));

        customizer().customise(openApi);

        assertThat(internal.getSecurity()).containsExactly(new SecurityRequirement().addList("X-Internal"));
        assertThat(errorNames(internal, "401")).containsExactlyInAnyOrder("INTERNAL_TOKEN_REJECTED", "INTERNAL_TOKEN_NOT_CONFIGURED");
        assertThat(errorNames(internal, "500")).containsExactly("INTERNAL_ERROR");
        assertThat(internal.getResponses().get("400")).isNull();

        assertThat(errorNames(getItem, "401")).containsExactly("UNAUTHORIZED", "INVALID_PARAMETER");
        assertThat(errorNames(getItem, "400")).containsExactly("INVALID_PARAMETER");
        assertThat(errorNames(getItem, "429")).containsExactly("RATE_LIMIT_EXCEEDED");
        ApiResponse success = getItem.getResponses().get("200");
        assertThat(success.getDescription()).isEqualTo("성공. 결과는 본문 `success`에 담긴다.");
        @SuppressWarnings("unchecked")
        Map<String, Object> example = (Map<String, Object>) success.getContent().get(ErrorResponses.JSON).getExample();
        assertThat(example).containsEntry("resultType", "SUCCESS")
                .containsEntry("success", Map.of("name", "이름"))
                .containsEntry("error", null);
        assertThat(errorNames(create, "400")).containsExactly("INVALID_PARAMETER");
        assertThat(errorNames(create, "415")).containsExactly("UNSUPPORTED_MEDIA_TYPE");
        assertThat(plain.getResponses().get("400")).isNull();
        assertThat(plain.getResponses().get("200").getDescription()).isEqualTo("성공");

        assertThat(openApi.getComponents().getSchemas()).containsKeys(ErrorResponses.FAIL_SCHEMA, "ErrorResponse");
        assertThat(openApi.getTags()).extracting(Tag::getName).containsExactly("미사용", "항목", "콘솔", "내부: 관리");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) openApi.getExtensions().get("x-tagGroups");
        assertThat(groups).extracting(group -> group.get("name"))
                .containsExactly("앱 API", "관리자 콘솔 API", "내부 API(서비스 간·관리자 백엔드)");
        assertThat(groups.getFirst().get("tags")).isEqualTo(List.of("항목"));
    }

    @Test
    void 전역_보안_요구사항의_인증_실패도_붙인다() {
        OpenAPI openApi = openApi().security(List.of(new SecurityRequirement().addList(MockcoinApiDocs.BEARER_AUTH)));
        Operation operation = operation("항목");
        openApi.paths(new Paths().addPathItem("/api/v1/me", new PathItem().get(operation)));

        customizer().customise(openApi);

        assertThat(errorNames(operation, "401")).containsExactly("UNAUTHORIZED", "INVALID_PARAMETER");
    }

    @Test
    void 문서_설정에_없는_보안_스킴을_쓰면_실패한다() {
        OpenAPI openApi = openApi();
        Operation operation = operation("항목").addSecurityItem(new SecurityRequirement().addList("unknown"));
        openApi.paths(new Paths().addPathItem("/api/v1/x", new PathItem().get(operation)));

        assertThatThrownBy(() -> customizer().customise(openApi))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void 한_태그가_앱_경로와_내부_경로에_걸치면_실패한다() {
        OpenAPI openApi = openApi();
        openApi.paths(new Paths()
                .addPathItem("/api/v1/x", new PathItem().get(operation("섞임")))
                .addPathItem("/internal/x", new PathItem().get(operation("섞임"))));

        assertThatThrownBy(() -> customizer().customise(openApi))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("섞임");
    }

    @Test
    void 태그가_없는_연산도_처리한다() {
        OpenAPI openApi = openApi();
        Operation operation = new Operation().responses(new ApiResponses());
        openApi.paths(new Paths().addPathItem("/api/v1/untagged", new PathItem().get(operation)));

        customizer().customise(openApi);

        assertThat(errorNames(operation, "500")).containsExactly("INTERNAL_ERROR");
        assertThat(openApi.getExtensions().get("x-tagGroups")).isEqualTo(List.of());
    }

    @Test
    void 경로가_없어도_에러_스키마를_등록한다() {
        OpenAPI openApi = new OpenAPI();

        customizer().customise(openApi);

        assertThat(openApi.getComponents().getSchemas()).containsKey(ErrorResponses.FAIL_SCHEMA);
    }

    @Test
    void 바인딩_실패_가능성을_파라미터_위치와_타입으로_판단한다() {
        assertThat(MockcoinOpenApiCustomizer.canFailBinding(new Parameter().in("path").schema(new Schema<>().type("string")))).isFalse();
        assertThat(MockcoinOpenApiCustomizer.canFailBinding(new Parameter().in("path").schema(new Schema<>().type("string").format("uuid")))).isTrue();
        assertThat(MockcoinOpenApiCustomizer.canFailBinding(new Parameter().in("query").required(true).schema(new Schema<>().type("string")))).isTrue();
        assertThat(MockcoinOpenApiCustomizer.canFailBinding(new Parameter().in("query").schema(new Schema<>().type("string")._enum(List.of("a"))))).isTrue();
        assertThat(MockcoinOpenApiCustomizer.canFailBinding(new Parameter().in("query").schema(new Schema<>().type("array").items(new Schema<>().type("integer"))))).isTrue();
        assertThat(MockcoinOpenApiCustomizer.canFailBinding(new Parameter().in("header"))).isFalse();
    }

    private static MockcoinOpenApiCustomizer customizer() {
        FilterRegistrationBean<InternalTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(
                new InternalTokenAuthenticationFilter("X-Internal", () -> "t", "sample internal token is not configured"));
        registration.addUrlPatterns("/internal/sample/*");
        MockcoinApiDocs docs = MockcoinApiDocs.builder("sample-service", 8099)
                .title("t").description("d")
                .bearerToken("토큰", 401, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.INVALID_PARAMETER)
                .internalTokenFilter(registration, "내부")
                .errorsForPaths(path -> path.startsWith("/api/"), CommonErrorCode.RATE_LIMIT_EXCEEDED)
                .build();
        return new MockcoinOpenApiCustomizer(docs, new ErrorResponses());
    }

    private static OpenAPI openApi() {
        Map<String, Schema> properties = new java.util.LinkedHashMap<>();
        properties.put("resultType", new Schema<>().type("string"));
        properties.put("success", new Schema<>().$ref("Item"));
        properties.put("error", new Schema<>().$ref("ErrorResponse"));
        return new OpenAPI().components(new Components()
                .addSchemas("ApiResponseItem", new Schema<>().type("object").properties(properties))
                .addSchemas("Item", new Schema<>().type("object").properties(Map.of("name", new Schema<>().type("string").example("이름")))));
    }

    private static Operation operation(String tag) {
        return new Operation().addTagsItem(tag).responses(new ApiResponses());
    }

    private static List<String> errorNames(Operation operation, String status) {
        return List.copyOf(operation.getResponses().get(status).getContent().get(ErrorResponses.JSON).getExamples().keySet());
    }
}
