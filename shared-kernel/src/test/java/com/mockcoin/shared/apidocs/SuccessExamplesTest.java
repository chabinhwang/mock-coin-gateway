package com.mockcoin.shared.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

class SuccessExamplesTest {

    @Test
    void 공통_봉투면_성공_모양으로_필드_예시를_모은다() {
        Map<String, Schema> schemas = new LinkedHashMap<>();
        schemas.put("ApiResponseItem", envelope("Item"));
        schemas.put("Item", object(Map.of(
                "name", string().example("이름"),
                "status", string()._enum(List.of("active", "ended")),
                "tags", new Schema<>().type("array").items(string().example("a")),
                "child", new Schema<>().$ref("Child"),
                "createdAt", string().format("date-time")
        )));
        schemas.put("Child", object(Map.of("parent", new Schema<>().$ref("Child"), "count", new Schema<>().type("integer"))));

        Object example = new SuccessExamples(schemas).envelopeExample(new Schema<>().$ref("ApiResponseItem"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) example;
        assertThat(body).containsEntry("resultType", "SUCCESS").containsEntry("error", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> success = (Map<String, Object>) body.get("success");
        assertThat(success).containsEntry("name", "이름")
                .containsEntry("status", "active")
                .containsEntry("tags", List.of("a"))
                .containsEntry("createdAt", "2026-01-01T00:00:00Z");
        assertThat(success.get("child")).isEqualTo(Map.of("parent", Map.of(), "count", 0));
    }

    @Test
    void 봉투가_아니면_예시를_만들지_않는다() {
        SuccessExamples examples = new SuccessExamples(null);

        assertThat(examples.envelopeExample(null)).isNull();
        assertThat(examples.envelopeExample(new Schema<>().$ref("Missing"))).isNull();
        assertThat(examples.envelopeExample(string())).isNull();
        assertThat(examples.envelopeExample(object(Map.of("other", string())))).isNull();
    }

    @Test
    void 타입별_기본값과_합성_스키마와_맵을_다룬다() {
        Schema<Object> success = object(new LinkedHashMap<>(Map.of(
                "examples31", string().examples(List.of("첫 예시")),
                "flag", new Schema<>().type("boolean"),
                "amount", new Schema<>().type("number"),
                "day", string().format("date"),
                "id", string().format("uuid"),
                "plain", string(),
                "composed", new Schema<>().oneOf(List.of(string().example("one"))),
                "map", new Schema<>().type("object").additionalProperties(new Schema<>().type("integer").example(7)),
                "empty", new Schema<>().type("array"),
                "untyped", new Schema<>()
        )));
        Map<String, Schema> schemas = Map.of("ApiResponseX", envelopeOf(success));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) new SuccessExamples(schemas).envelopeExample(new Schema<>().$ref("ApiResponseX"));
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) body.get("success");

        assertThat(values).containsEntry("examples31", "첫 예시")
                .containsEntry("flag", false)
                .containsEntry("amount", 0)
                .containsEntry("day", "2026-01-01")
                .containsEntry("id", "00000000-0000-0000-0000-000000000000")
                .containsEntry("plain", "string")
                .containsEntry("composed", "one")
                .containsEntry("map", Map.of("key", 7))
                .containsEntry("empty", List.of())
                .containsEntry("untyped", null);
    }

    @Test
    void OpenAPI_3_1_타입_집합에서_null이_아닌_타입을_고른다() {
        Schema<Object> schema = new Schema<>().specVersion(SpecVersion.V31);
        schema.setTypes(Set.of("null", "string"));

        assertThat(SuccessExamples.type(schema)).isEqualTo("string");
        assertThat(SuccessExamples.type(new Schema<>())).isNull();
        assertThat(SuccessExamples.refName("Plain")).isEqualTo("Plain");
    }

    private static Schema<Object> envelope(String successRef) {
        return envelopeOf(new Schema<>().$ref(successRef));
    }

    private static Schema<Object> envelopeOf(Schema<?> success) {
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("resultType", string());
        properties.put("success", success);
        properties.put("error", new Schema<>().$ref("ErrorResponse"));
        return new Schema<>().type("object").properties(properties);
    }

    private static Schema<Object> object(Map<String, Schema> properties) {
        return new Schema<>().type("object").properties(new LinkedHashMap<>(properties));
    }

    private static Schema<Object> string() {
        return new Schema<>().type("string");
    }
}
