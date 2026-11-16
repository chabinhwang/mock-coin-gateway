package com.mockcoin.shared.apidocs.testing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// 생성된 명세에서 설명이 빠진 곳을 찾는다. 처음 보는 사람이 문서만 보고 호출할 수 있도록 다음을 강제한다.
// - 서비스 설명, 모든 태그 설명, 모든 API의 summary/description
// - 모든 파라미터와 스키마 필드의 설명, 값 필드(문자열·숫자·불리언)의 예시
// - enum 값마다 뜻 — 설명에 모든 enum 값이 적혀 있어야 한다
public final class ApiDocsLint {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");
    private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "number", "boolean");

    private ApiDocsLint() {
    }

    public static void assertDocumented(String specJson) {
        JsonNode spec = read(specJson);
        List<String> problems = new ArrayList<>();
        if (blank(spec.path("info").path("description"))) {
            problems.add("info.description(서비스 설명)이 없습니다.");
        }
        Set<String> describedTags = new HashSet<>();
        spec.path("tags").forEach(tag -> {
            if (blank(tag.path("description"))) {
                problems.add("태그 설명이 없습니다: " + tag.path("name").asText());
            } else {
                describedTags.add(tag.path("name").asText());
            }
        });
        fields(spec.path("paths")).forEach(path -> fields(path.getValue()).forEach(method -> {
            if (!HTTP_METHODS.contains(method.getKey())) {
                return;
            }
            String where = method.getKey().toUpperCase() + " " + path.getKey();
            checkOperation(where, method.getValue(), describedTags, problems);
        }));
        fields(spec.path("components").path("schemas")).forEach(schema ->
                checkSchema("schema " + schema.getKey(), schema.getValue(), true, problems));
        if (!problems.isEmpty()) {
            throw new AssertionError("API 문서 설명이 빠진 곳이 " + problems.size() + "개 있습니다. "
                    + "컨트롤러·DTO에 @Tag/@Operation/@Parameter/@Schema로 설명과 예시를 다세요.\n- "
                    + String.join("\n- ", problems));
        }
    }

    private static void checkOperation(String where, JsonNode operation, Set<String> describedTags, List<String> problems) {
        if (blank(operation.path("summary"))) {
            problems.add(where + ": summary가 없습니다.");
        }
        if (blank(operation.path("description"))) {
            problems.add(where + ": description이 없습니다.");
        }
        if (!operation.path("tags").isArray() || operation.path("tags").isEmpty()) {
            problems.add(where + ": 태그가 없습니다.");
        }
        operation.path("tags").forEach(tag -> {
            if (!describedTags.contains(tag.asText())) {
                problems.add(where + ": 설명이 있는 태그(@Tag)에 속하지 않습니다: " + tag.asText());
            }
        });
        operation.path("parameters").forEach(parameter -> {
            String name = where + " 파라미터 " + parameter.path("name").asText();
            if (blank(parameter.path("description"))) {
                problems.add(name + ": 설명이 없습니다.");
            }
            JsonNode schema = parameter.path("schema");
            if (needsExample(schema) && !hasExample(parameter) && !hasExample(schema)) {
                problems.add(name + ": 예시 값이 없습니다.");
            }
            checkEnumDescribed(name, schema, parameter.path("description").asText(), problems);
        });
    }

    private static void checkSchema(String where, JsonNode schema, boolean component, List<String> problems) {
        if (component && blank(schema.path("description")) && schema.has("properties")) {
            problems.add(where + ": 설명이 없습니다.");
        }
        fields(schema.path("properties")).forEach(property -> {
            String name = where + "." + property.getKey();
            JsonNode value = property.getValue();
            if (blank(value.path("description"))) {
                problems.add(name + ": 설명이 없습니다.");
            }
            if (needsExample(value) && !hasExample(value)) {
                problems.add(name + ": 예시 값이 없습니다.");
            }
            checkEnumDescribed(name, value, value.path("description").asText(), problems);
            if (value.has("properties")) {
                checkSchema(name, value, false, problems);
            }
        });
    }

    // enum 값마다 뜻을 적었는지 — 설명에 모든 값이 나와야 한다
    private static void checkEnumDescribed(String where, JsonNode schema, String description, List<String> problems) {
        JsonNode values = schema.has("enum") ? schema.path("enum") : schema.path("items").path("enum");
        values.forEach(value -> {
            if (!value.isNull() && !description.contains(value.asText())) {
                problems.add(where + ": 설명에 enum 값 '" + value.asText() + "'의 뜻이 없습니다.");
            }
        });
    }

    private static boolean needsExample(JsonNode schema) {
        if (schema.has("$ref") || schema.has("enum")) {
            return false;
        }
        String type = type(schema);
        if ("array".equals(type)) {
            return needsExample(schema.path("items"));
        }
        return type != null && SCALAR_TYPES.contains(type) && !"binary".equals(schema.path("format").asText());
    }

    private static boolean hasExample(JsonNode node) {
        return node.has("example") || (node.has("examples") && !node.path("examples").isEmpty())
                || (node.path("items").has("example")) || (node.path("items").has("examples"));
    }

    private static String type(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) {
            return type.asText();
        }
        if (type.isArray()) {
            for (JsonNode each : type) {
                if (!"null".equals(each.asText())) {
                    return each.asText();
                }
            }
        }
        return null;
    }

    private static boolean blank(JsonNode node) {
        return !node.isTextual() || node.asText().isBlank();
    }

    private static Iterable<Map.Entry<String, JsonNode>> fields(JsonNode node) {
        return node.properties();
    }

    static JsonNode read(String specJson) {
        try {
            return MAPPER.readTree(specJson);
        } catch (IOException exception) {
            throw new IllegalStateException("API 명세 JSON을 읽지 못했습니다.", exception);
        }
    }
}
