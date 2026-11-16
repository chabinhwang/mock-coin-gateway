package com.mockcoin.shared.apidocs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.mockcoin.shared.api.CommonErrorCode;
import com.mockcoin.shared.api.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;

// 모든 API에 공통인 문서 규칙을 마지막에 한 번에 적용한다.
// - 경로로 보호하는 보안 스킴(내부 토큰 필터)을 해당 API에 붙이고, 붙은 보안 스킴의 인증 실패 에러를 더한다.
// - 경로 규칙 에러(요청 한도 등), 요청 형식 에러(400/415), 서버 오류(500)를 더한다.
// - 에러 응답 설명을 에러 코드 목록으로 쓰고, 성공 응답에 예시 본문을 만든다.
final class MockcoinOpenApiCustomizer implements OpenApiCustomizer {
    private final MockcoinApiDocs docs;
    private final ErrorResponses errorResponses;
    private final Map<String, TagGroup> tagGroups = new java.util.concurrent.ConcurrentHashMap<>();

    // 서비스 문서 정의와 에러 응답 등록기를 주입받는다.
    MockcoinOpenApiCustomizer(MockcoinApiDocs docs, ErrorResponses errorResponses) {
        this.docs = docs;
        this.errorResponses = errorResponses;
    }

    // 공통 실패 스키마를 등록하고, 경로 순으로 각 연산에 태그 묶음·보안·공통 에러·성공 예시를 적용한 뒤 x-tagGroups를 쓴다.
    @Override
    public void customise(OpenAPI openApi) {
        addErrorSchemas(openApi);
        SuccessExamples successExamples = new SuccessExamples(openApi.getComponents().getSchemas());
        if (openApi.getPaths() == null) {
            return;
        }
        new TreeMap<>(openApi.getPaths()).forEach((path, pathItem) ->
                pathItem.readOperationsMap().values().forEach(operation -> {
                    groupTags(path, operation);
                    applyPathSchemes(path, operation);
                    addSecurityFailures(operation, openApi.getSecurity());
                    addPathRuleErrors(path, operation);
                    addRequestShapeErrors(operation);
                    errorResponses.add(operation, DocumentedError.of(CommonErrorCode.INTERNAL_ERROR));
                    errorResponses.describe(operation);
                    describeSuccess(operation, successExamples);
                }));
        writeTagGroups(openApi);
    }

    // 태그를 경로로 묶는다: /internal/** = 내부 API, /admin/** = 관리자 콘솔 API, 나머지 = 앱 API.
    // 한 태그가 여러 묶음에 걸치면 문서에서 외부·내부 구분이 흐려지므로 막는다.
    private void groupTags(String path, Operation operation) {
        TagGroup group = TagGroup.of(path);
        if (operation.getTags() == null) {
            return;
        }
        for (String tag : operation.getTags()) {
            TagGroup previous = tagGroups.putIfAbsent(tag, group);
            if (previous != null && previous != group) {
                throw new IllegalStateException("태그 '" + tag + "'에 " + previous.title() + "와 " + group.title()
                        + " 경로가 섞여 있습니다. 컨트롤러 태그를 나누세요.");
            }
        }
    }

    // 태그 목록을 묶음 순서로 정렬하고 x-tagGroups(Scalar 등이 읽는 묶음 정보)를 쓴다
    private void writeTagGroups(OpenAPI openApi) {
        if (openApi.getTags() != null) {
            openApi.setTags(openApi.getTags().stream()
                    .sorted(Comparator
                            .comparing((Tag tag) -> tagGroups.getOrDefault(tag.getName(), TagGroup.APP))
                            .thenComparing(Tag::getName))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (TagGroup group : TagGroup.values()) {
            List<String> tags = tagGroups.entrySet().stream()
                    .filter(entry -> entry.getValue() == group)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            if (!tags.isEmpty()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", group.title());
                entry.put("tags", tags);
                groups.add(entry);
            }
        }
        openApi.addExtension("x-tagGroups", groups);
    }

    // 경로 접두사(/internal, /admin, 그 외)로 정하는 x-tagGroups 묶음과 표시 제목
    enum TagGroup {
        APP("앱 API"),
        ADMIN_CONSOLE("관리자 콘솔 API"),
        INTERNAL("내부 API(서비스 간·관리자 백엔드)");

        private final String title;

        TagGroup(String title) {
            this.title = title;
        }

        String title() {
            return title;
        }

        // 경로가 /internal이면 내부, /admin이면 관리자 콘솔, 그 외는 앱 API 묶음으로 정한다.
        static TagGroup of(String path) {
            if (path.startsWith("/internal/") || path.equals("/internal")) {
                return INTERNAL;
            }
            if (path.startsWith("/admin/") || path.equals("/admin")) {
                return ADMIN_CONSOLE;
            }
            return APP;
        }
    }

    // URL 패턴이 경로에 걸리는 보안 스킴을, 연산에 아직 없을 때만 보안 요구사항으로 붙인다.
    private void applyPathSchemes(String path, Operation operation) {
        docs.schemes().values().stream()
                .filter(scheme -> !scheme.urlPatterns().isEmpty())
                .filter(scheme -> ServletUrlPatterns.anyMatches(scheme.urlPatterns(), path))
                .sorted((left, right) -> left.name().compareTo(right.name()))
                .forEach(scheme -> {
                    boolean present = operation.getSecurity() != null && operation.getSecurity().stream()
                            .anyMatch(requirement -> requirement.containsKey(scheme.name()));
                    if (!present) {
                        operation.addSecurityItem(new SecurityRequirement().addList(scheme.name()));
                    }
                });
    }

    // 연산(없으면 전역) 보안 요구사항의 스킴별 인증 실패 에러를 더하고, 설정에 없는 스킴이면 IllegalStateException을 던진다.
    private void addSecurityFailures(Operation operation, List<SecurityRequirement> globalSecurity) {
        List<SecurityRequirement> requirements = operation.getSecurity() != null ? operation.getSecurity() : globalSecurity;
        if (requirements == null) {
            return;
        }
        Set<String> schemeNames = new LinkedHashSet<>();
        requirements.forEach(requirement -> schemeNames.addAll(requirement.keySet()));
        for (String schemeName : schemeNames) {
            MockcoinApiDocs.SchemeDefinition scheme = docs.schemes().get(schemeName);
            if (scheme == null) {
                throw new IllegalStateException("문서 설정에 없는 보안 스킴입니다: " + schemeName
                        + " (operationId=" + operation.getOperationId() + ")");
            }
            scheme.failures().forEach(failure -> errorResponses.add(operation, failure));
        }
    }

    // 경로 조건에 맞는 공통 에러 규칙의 에러를 연산에 더한다.
    private void addPathRuleErrors(String path, Operation operation) {
        docs.pathErrorRules().stream()
                .filter(rule -> rule.paths().test(path))
                .forEach(rule -> rule.errors().forEach(error -> errorResponses.add(operation, error)));
    }

    // 바인딩 단계에서 실패할 수 있는 입력이 있으면 400, 요청 본문이 있으면 415를 더한다
    private void addRequestShapeErrors(Operation operation) {
        boolean hasBody = operation.getRequestBody() != null;
        boolean bindable = hasBody || (operation.getParameters() != null
                && operation.getParameters().stream().anyMatch(MockcoinOpenApiCustomizer::canFailBinding));
        if (bindable) {
            errorResponses.add(operation, DocumentedError.of(CommonErrorCode.INVALID_PARAMETER));
        }
        if (hasBody) {
            errorResponses.add(operation, DocumentedError.of(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE));
        }
    }

    // 필수 쿼리·헤더 값이 빠지거나, 문자열이 아닌 타입으로 변환하지 못하면 400이 난다(경로 변수는 빠질 수 없다)
    static boolean canFailBinding(Parameter parameter) {
        boolean typed = isTyped(parameter.getSchema());
        if ("path".equals(parameter.getIn())) {
            return typed;
        }
        return typed || Boolean.TRUE.equals(parameter.getRequired());
    }

    // 스키마가 문자열이 아닌 타입이거나 format·enum을 가져 변환에 실패할 수 있는지 확인한다(배열은 항목 기준).
    private static boolean isTyped(Schema<?> schema) {
        if (schema == null) {
            return false;
        }
        String type = SuccessExamples.type(schema);
        if ("array".equals(type)) {
            return isTyped(schema.getItems());
        }
        return (type != null && !"string".equals(type)) || schema.getFormat() != null || schema.getEnum() != null;
    }

    // 2xx JSON 응답에 예시가 없으면 성공 봉투 예시를 넣고, 설명이 비었거나 "OK"면 성공 설명으로 바꾼다.
    private static void describeSuccess(Operation operation, SuccessExamples successExamples) {
        if (operation.getResponses() == null) {
            return;
        }
        operation.getResponses().forEach((status, response) -> {
            if (!status.startsWith("2")) {
                return;
            }
            MediaType json = response.getContent() == null ? null : response.getContent().get(ErrorResponses.JSON);
            Object example = json == null || json.getSchema() == null ? null : successExamples.envelopeExample(json.getSchema());
            if (example != null && json.getExample() == null && json.getExamples() == null) {
                json.setExample(example);
            }
            if (response.getDescription() == null || response.getDescription().isBlank() || "OK".equals(response.getDescription())) {
                response.setDescription(example != null ? "성공. 결과는 본문 `success`에 담긴다." : "성공");
            }
        });
    }

    // ErrorResponse 스키마와 실패 봉투 스키마(ApiFailResponse)를 components에 등록한다.
    private static void addErrorSchemas(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        ModelConverters.getInstance(true).readAll(ErrorResponse.class)
                .forEach((name, schema) -> {
                    if (components.getSchemas() == null || !components.getSchemas().containsKey(name)) {
                        components.addSchemas(name, schema);
                    }
                });
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put("resultType", schema("string")
                .description("실패 응답은 항상 `FAIL`이다.")
                ._enum(List.of("FAIL")));
        properties.put("success", schema("null").description("실패 응답에서는 항상 `null`이다."));
        properties.put("error", new Schema<>().specVersion(SpecVersion.V31).$ref(ErrorResponse.class.getSimpleName())
                .description("실패 원인. `errorCode`로 분기한다."));
        components.addSchemas(ErrorResponses.FAIL_SCHEMA, schema("object")
                .description("실패 응답 본문. 공통 봉투에서 `resultType=FAIL`, `success=null`, `error`가 채워진 모양이다.")
                .properties(properties)
                .required(List.of("resultType", "success", "error")));
    }

    // OpenAPI 3.1 형식으로 지정한 타입의 스키마를 만든다.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<Object> schema(String type) {
        Schema schema = new Schema<>().specVersion(SpecVersion.V31);
        schema.addType(type);
        return schema;
    }
}
