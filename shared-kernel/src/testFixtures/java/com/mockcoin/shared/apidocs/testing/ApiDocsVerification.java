package com.mockcoin.shared.apidocs.testing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.mockcoin.shared.api.ApiErrorDeclarations;
import com.mockcoin.shared.api.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

// 서비스별 API 문서 테스트가 쓰는 검증 묶음.
// 1) 코드에서 만든 명세가 커밋된 명세 파일과 같은지 2) 설명이 빠진 곳이 없는지
// 3) 컨트롤러에 선언한 에러 코드가 실제로 던질 수 있는 에러와 맞는지 4) 응답·요청 DTO 스키마 이름이 겹치지 않는지
public final class ApiDocsVerification {
    // 명세 파일 비교(=생성) 테스트에 붙이는 JUnit 태그. generateApiDocs Gradle 태스크가 이 태그만 실행한다.
    public static final String SNAPSHOT_TAG = "api-docs-snapshot";
    private static final String HANDLER_EXTENSION = "x-handler";
    private static final String APPLICATION_PACKAGE = "com.mockcoin.";

    private final String serviceName;
    private final String spec;
    private final List<HandlerMethod> handlers;

    private ApiDocsVerification(String serviceName, String spec, List<HandlerMethod> handlers) {
        this.serviceName = serviceName;
        this.spec = spec;
        this.handlers = handlers;
    }

    // MockMvc로 /v3/api-docs를 받아(서버를 띄우지 않는다) 검증을 준비한다
    public static ApiDocsVerification of(String serviceName, MockMvc mockMvc, RequestMappingHandlerMapping handlerMapping) {
        try {
            MvcResult result = mockMvc.perform(get("/v3/api-docs")).andReturn();
            if (result.getResponse().getStatus() != 200) {
                throw new AssertionError("/v3/api-docs 응답이 200이 아닙니다: " + result.getResponse().getStatus()
                        + " — 테스트에 springdoc.api-docs.enabled=true를 주었는지 확인하세요.");
            }
            String spec = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            List<HandlerMethod> handlers = handlerMapping.getHandlerMethods().values().stream()
                    .filter(handler -> ClassUtils.getUserClass(handler.getBeanType()).getName().startsWith(APPLICATION_PACKAGE))
                    .distinct()
                    .toList();
            return new ApiDocsVerification(serviceName, spec, handlers);
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("API 명세를 받지 못했습니다.", exception);
        }
    }

    public String spec() {
        return spec;
    }

    // 운영 기본 설정(application.yml, 환경변수 없음)에서 명세 엔드포인트가 꺼져 있어야 한다
    public static void assertApiDocsDisabledByDefault() {
        String property = "springdoc.api-docs.enabled";
        try {
            List<org.springframework.core.env.PropertySource<?>> sources = new org.springframework.boot.env.YamlPropertySourceLoader()
                    .load("application.yml", new org.springframework.core.io.ClassPathResource("application.yml"));
            Object raw = sources.stream().map(source -> source.getProperty(property)).filter(Objects::nonNull).findFirst().orElse(null);
            if (raw == null) {
                throw new AssertionError("application.yml에 " + property + " 설정이 없습니다. 기본값 false로 명시하세요.");
            }
            String resolved = new org.springframework.util.PropertyPlaceholderHelper("${", "}", ":", null, true)
                    .replacePlaceholders(raw.toString(), placeholder -> null);
            if (!"false".equals(resolved)) {
                throw new AssertionError(property + " 기본값이 false가 아닙니다(운영 노출 금지): " + raw);
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("application.yml을 읽지 못했습니다.", exception);
        }
    }

    // 1) 커밋된 명세 파일과 같아야 한다(갱신 모드면 파일을 다시 쓴다)
    public void assertMatchesCommittedSpec() {
        ApiDocsSnapshot.verify(serviceName, spec);
    }

    // 2) 서비스·태그·API·파라미터·필드 설명과 예시가 모두 있어야 한다
    public void assertDocumented() {
        ApiDocsLint.assertDocumented(spec);
    }

    // 호출 그래프로는 닿지만 응답으로는 나오지 않는 에러(중간에서 잡아 처리하는 등). 코드상 근거를 reason에 적는다.
    public record NotReturned(Class<?> controller, String method, ErrorCode error, String reason) {
        public NotReturned {
            Objects.requireNonNull(controller, "controller must not be null");
            Objects.requireNonNull(method, "method must not be null");
            Objects.requireNonNull(error, "error must not be null");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("응답으로 나오지 않는 이유를 적어야 합니다: " + controller.getSimpleName() + "#" + method);
            }
        }

        String handler() {
            return controller.getName() + "#" + method;
        }
    }

    public static NotReturned notReturned(Class<?> controller, String method, ErrorCode error, String reason) {
        return new NotReturned(controller, method, error, reason);
    }

    // 3) 컨트롤러에서 도달할 수 있는 에러는 모두 문서에 있어야 하고, 선언했지만 도달하지 않는 에러는 없어야 한다.
    //    호출 그래프는 try/catch를 보지 않는 과대 근사이므로, 잡혀서 응답으로 나오지 않는 에러는 notReturned로 이유와 함께 뺀다.
    public void assertErrorDeclarationsMatchCode(NotReturned... notReturned) {
        Map<String, Set<String>> excluded = new HashMap<>();
        for (NotReturned exclusion : notReturned) {
            excluded.computeIfAbsent(exclusion.handler(), key -> new TreeSet<>()).add(exclusion.error().name());
        }
        Map<String, List<Set<String>>> documented = documentedErrors(ApiDocsLint.read(spec));
        Set<Class<?>> roots = new LinkedHashSet<>();
        roots.add(ErrorCode.class);
        handlers.forEach(handler -> roots.add(ClassUtils.getUserClass(handler.getBeanType())));
        ErrorCodeCallGraph graph = ErrorCodeCallGraph.load(roots);

        List<String> problems = new ArrayList<>();
        Map<String, Method> byName = new TreeMap<>();
        for (HandlerMethod handler : handlers) {
            String name = ClassUtils.getUserClass(handler.getBeanType()).getName() + "#" + handler.getMethod().getName();
            Method previous = byName.put(name, handler.getMethod());
            if (previous != null && !previous.equals(handler.getMethod())) {
                problems.add(name + ": 같은 컨트롤러에 이름이 같은 핸들러 메서드가 있어 문서와 연결할 수 없습니다. 메서드 이름을 다르게 하세요.");
            }
        }
        byName.forEach((name, method) -> {
            List<Set<String>> operations = documented.get(name);
            if (operations == null) {
                problems.add(name + ": 명세에 없는 API입니다(@Hidden 등으로 빠졌는지 확인).");
                return;
            }
            ErrorCodeCallGraph.Reach reach = graph.reach(method);
            reach.rawErrorSites().forEach(site -> problems.add(name + ": ErrorCode 없이 에러를 만듭니다 — " + site
                    + " (서비스 ErrorCode enum의 exception()/relay()나 ApiResponse.fail(ErrorCode)를 쓰세요)"));
            Set<String> notReturnedErrors = excluded.getOrDefault(name, Set.of());
            for (String error : notReturnedErrors) {
                if (!reach.errors().containsKey(error)) {
                    problems.add(name + ": notReturned로 뺀 에러 " + error + "는 이제 호출 경로에 없습니다. 테스트의 notReturned에서 지우세요.");
                }
                if (ApiErrorDeclarations.of(method).stream().anyMatch(declared -> declared.name().equals(error))) {
                    problems.add(name + ": 에러 " + error + "를 선언하면서 notReturned로도 뺐습니다. 둘 중 하나만 두세요.");
                }
            }
            for (Set<String> operationErrors : operations) {
                Set<String> missing = new TreeSet<>(reach.errors().keySet());
                missing.removeAll(operationErrors);
                missing.removeAll(notReturnedErrors);
                missing.forEach(error -> problems.add(name + ": 도달할 수 있는 에러 " + describe(reach.errors().get(error))
                        + "가 문서에 없습니다. 에러 선언 어노테이션에 추가하세요."));
            }
            Set<String> stale = new TreeSet<>();
            ApiErrorDeclarations.of(method).forEach(declared -> {
                if (!reach.errors().containsKey(declared.name())) {
                    stale.add(describe(declared));
                }
            });
            stale.forEach(error -> problems.add(name + ": 선언한 에러 " + error
                    + "는 이 API의 호출 경로에서 던지지 않습니다. 선언에서 빼세요."));
        });
        excluded.keySet().stream()
                .filter(handler -> !byName.containsKey(handler))
                .forEach(handler -> problems.add(handler + ": notReturned에 적은 API가 없습니다."));
        if (!problems.isEmpty()) {
            throw new AssertionError("에러 선언이 코드와 맞지 않는 곳이 " + problems.size() + "개 있습니다.\n- "
                    + String.join("\n- ", problems));
        }
    }

    // 5) 명세의 필드 이름이 실제 응답 JSON(앱의 Jackson 직렬화 결과)과 같아야 한다.
    //    record DTO마다 표본 값을 만들어 직렬화한 키 집합과 명세 스키마의 properties 키 집합을 비교한다.
    public void assertSchemaPropertiesMatchJson(Function<Object, String> serializer) {
        JsonNode schemas = ApiDocsLint.read(spec).path("components").path("schemas");
        List<String> problems = new ArrayList<>();
        for (Class<?> type : dtoTypes()) {
            if (!type.isRecord()) {
                continue;
            }
            Schema annotation = type.getAnnotation(Schema.class);
            String name = annotation != null && !annotation.name().isBlank() ? annotation.name() : type.getSimpleName();
            JsonNode schema = schemas.path(name);
            if (!schema.has("properties")) {
                continue;
            }
            Set<String> documented = new TreeSet<>();
            schema.path("properties").fieldNames().forEachRemaining(documented::add);
            Set<String> serialized = new TreeSet<>();
            ApiDocsLint.read(serializer.apply(SampleValues.of(type))).fieldNames().forEachRemaining(serialized::add);
            if (!documented.equals(serialized)) {
                problems.add(type.getName() + ": 명세 " + documented + " / 실제 JSON " + serialized);
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("명세의 필드 이름이 실제 응답 JSON과 다릅니다. Jackson 이름 규칙(@JsonProperty 등)을 확인하세요.\n- "
                    + String.join("\n- ", problems));
        }
    }

    private Set<Class<?>> dtoTypes() {
        Map<String, Set<String>> ignored = new TreeMap<>();
        Set<Class<?>> visited = new LinkedHashSet<>();
        for (HandlerMethod handler : handlers) {
            collectTypes(handler.getMethod().getGenericReturnType(), ignored, visited);
            for (MethodParameter parameter : handler.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(RequestBody.class)) {
                    collectTypes(parameter.getGenericParameterType(), ignored, visited);
                }
            }
        }
        return visited.stream()
                .filter(type -> type.getName().startsWith(APPLICATION_PACKAGE))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    // 4) 서로 다른 DTO 클래스가 같은 스키마 이름을 쓰면 한쪽 스키마가 다른 쪽을 덮어쓰므로 막는다
    public void assertUniqueSchemaNames() {
        Map<String, Set<String>> classesByName = new TreeMap<>();
        Set<Class<?>> visited = new HashSet<>();
        for (HandlerMethod handler : handlers) {
            collectTypes(handler.getMethod().getGenericReturnType(), classesByName, visited);
            for (MethodParameter parameter : handler.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(RequestBody.class)) {
                    collectTypes(parameter.getGenericParameterType(), classesByName, visited);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        classesByName.forEach((name, classes) -> {
            if (classes.size() > 1) {
                problems.add(name + " ← " + String.join(", ", classes));
            }
        });
        if (!problems.isEmpty()) {
            throw new AssertionError("서로 다른 클래스가 같은 API 스키마 이름을 씁니다. 클래스 이름을 바꾸거나 @Schema(name = ...)로 구분하세요.\n- "
                    + String.join("\n- ", problems));
        }
    }

    private static void collectTypes(Type type, Map<String, Set<String>> classesByName, Set<Class<?>> visited) {
        if (type instanceof ParameterizedType parameterized) {
            collectTypes(parameterized.getRawType(), classesByName, visited);
            for (Type argument : parameterized.getActualTypeArguments()) {
                collectTypes(argument, classesByName, visited);
            }
            return;
        }
        if (!(type instanceof Class<?> type1) || !visited.add(type1)) {
            return;
        }
        if (type1.isArray()) {
            collectTypes(type1.getComponentType(), classesByName, visited);
            return;
        }
        if (!type1.getName().startsWith(APPLICATION_PACKAGE) || type1.isEnum()) {
            return;
        }
        Schema schema = type1.getAnnotation(Schema.class);
        String name = schema != null && !schema.name().isBlank() ? schema.name() : type1.getSimpleName();
        classesByName.computeIfAbsent(name, key -> new TreeSet<>()).add(type1.getName());
        if (type1.isRecord()) {
            for (RecordComponent component : type1.getRecordComponents()) {
                collectTypes(component.getGenericType(), classesByName, visited);
            }
            return;
        }
        for (java.lang.reflect.Field field : type1.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                collectTypes(field.getGenericType(), classesByName, visited);
            }
        }
    }

    private static Map<String, List<Set<String>>> documentedErrors(JsonNode spec) {
        Map<String, List<Set<String>>> documented = new HashMap<>();
        spec.path("paths").forEach(pathItem -> pathItem.forEach(operation -> {
            if (!operation.has(HANDLER_EXTENSION)) {
                return;
            }
            Set<String> errors = new HashSet<>();
            operation.path("responses").forEach(response ->
                    response.path("content").forEach(media -> media.path("examples").fieldNames().forEachRemaining(errors::add)));
            documented.computeIfAbsent(operation.path(HANDLER_EXTENSION).asText(), key -> new ArrayList<>()).add(errors);
        }));
        return documented;
    }

    private static String describe(ErrorCode definition) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("status", definition.status());
        parts.put("code", definition.code());
        return definition.getClass().getSimpleName() + "." + definition.name() + parts;
    }
}
