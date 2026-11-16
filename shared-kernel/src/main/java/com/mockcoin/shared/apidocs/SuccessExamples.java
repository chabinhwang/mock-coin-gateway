package com.mockcoin.shared.apidocs;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.media.Schema;

// 응답 스키마의 필드 예시 값(@Schema(example))을 모아 성공 응답 예시 본문을 만든다.
// 공통 봉투(ApiResponseXxx)는 성공 모양(resultType=SUCCESS, error=null)으로 채운다.
final class SuccessExamples {
    private static final String REF_PREFIX = "#/components/schemas/";
    private static final Set<String> ENVELOPE_PROPERTIES = Set.of("resultType", "success", "error");

    private final Map<String, Schema> schemas;

    // components 스키마 맵을 받고, 없으면 빈 맵을 쓴다.
    SuccessExamples(Map<String, Schema> schemas) {
        this.schemas = schemas == null ? Map.of() : schemas;
    }

    // 공통 봉투 스키마면 성공 예시를, 아니면 null을 돌려준다
    Object envelopeExample(Schema<?> schema) {
        Schema<?> resolved = resolve(schema);
        if (resolved == null || resolved.getProperties() == null
                || !resolved.getProperties().keySet().equals(ENVELOPE_PROPERTIES)) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resultType", "SUCCESS");
        body.put("success", example(resolved.getProperties().get("success"), new ArrayDeque<>()));
        body.put("error", null);
        return body;
    }

    // 스키마 예시 값을 $ref → example → enum → 조합 첫 항목 → 배열·객체 → 타입 기본값 순으로 재귀 생성하고, 순환 참조는 빈 객체로 끊는다.
    private Object example(Schema<?> schema, Deque<String> visiting) {
        if (schema == null) {
            return null;
        }
        if (schema.get$ref() != null) {
            String name = refName(schema.get$ref());
            if (visiting.contains(name)) {
                return Map.of();
            }
            visiting.push(name);
            try {
                return example(schemas.get(name), visiting);
            } finally {
                visiting.pop();
            }
        }
        if (schema.getExample() != null) {
            return schema.getExample();
        }
        if (schema.getExamples() != null && !schema.getExamples().isEmpty()) {
            return schema.getExamples().getFirst();
        }
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            return schema.getEnum().getFirst();
        }
        for (List<Schema> composed : java.util.Arrays.asList(schema.getAllOf(), schema.getOneOf(), schema.getAnyOf())) {
            if (composed != null && !composed.isEmpty()) {
                return example(composed.getFirst(), visiting);
            }
        }
        String type = type(schema);
        if ("array".equals(type)) {
            Object item = example(schema.getItems(), visiting);
            return item == null ? List.of() : List.of(item);
        }
        if ("object".equals(type) || schema.getProperties() != null) {
            Map<String, Object> object = new LinkedHashMap<>();
            if (schema.getProperties() != null) {
                schema.getProperties().forEach((name, property) -> object.put(name, example(property, visiting)));
            }
            if (schema.getAdditionalProperties() instanceof Schema<?> valueSchema) {
                object.put("key", example(valueSchema, visiting));
            }
            return object;
        }
        return scalar(type, schema.getFormat());
    }

    // $ref 스키마면 components에서 찾은 실제 스키마를, 아니면 그대로 돌려준다.
    private Schema<?> resolve(Schema<?> schema) {
        if (schema != null && schema.get$ref() != null) {
            return schemas.get(refName(schema.get$ref()));
        }
        return schema;
    }

    // 타입·포맷별 기본 예시 값(숫자 0, false, 날짜·UUID·문자열 샘플)을 돌려주고, 모르는 타입이면 null을 돌려준다.
    private static Object scalar(String type, String format) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "integer", "number" -> 0;
            case "boolean" -> false;
            case "string" -> switch (format == null ? "" : format) {
                case "date-time" -> "2026-01-01T00:00:00Z";
                case "date" -> "2026-01-01";
                case "uuid" -> "00000000-0000-0000-0000-000000000000";
                default -> "string";
            };
            default -> null;
        };
    }

    // 스키마 타입을 돌려주고, types 목록이면 "null"을 뺀 첫 타입(이름순)을, 둘 다 없으면 null을 돌려준다.
    static String type(Schema<?> schema) {
        if (schema.getType() != null) {
            return schema.getType();
        }
        if (schema.getTypes() != null) {
            return schema.getTypes().stream().filter(type -> !"null".equals(type)).sorted().findFirst().orElse(null);
        }
        return null;
    }

    // "#/components/schemas/" 접두사를 떼어 스키마 이름을 돌려준다.
    static String refName(String ref) {
        return ref.startsWith(REF_PREFIX) ? ref.substring(REF_PREFIX.length()) : ref;
    }
}
