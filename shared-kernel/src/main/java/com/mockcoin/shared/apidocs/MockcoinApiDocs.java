package com.mockcoin.shared.apidocs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import com.mockcoin.shared.api.CommonErrorCode;
import com.mockcoin.shared.api.ErrorCode;
import com.mockcoin.shared.security.InternalTokenAuthenticationFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

// 한 서비스의 API 문서 정의(제목·설명·보안 스킴·공통 에러 규칙).
// 서비스별 문서 설정 클래스가 빈으로 등록하면 MockcoinApiDocsConfiguration이 springdoc에 연결한다.
public final class MockcoinApiDocs {
    // 컨트롤러의 @SecurityRequirement(name = ...)에 쓰는 보안 스킴 이름
    public static final String BEARER_AUTH = "bearerAuth";
    public static final String GATEWAY_USER_ID = "X-Mockcoin-User-Id";

    private final String serviceName;
    private final String title;
    private final String description;
    private final int port;
    private final Map<String, SchemeDefinition> schemes;
    private final List<PathErrorRule> pathErrorRules;

    // 빌더 값을 복사해 불변 문서 정의를 만든다(제목·설명은 필수).
    private MockcoinApiDocs(Builder builder) {
        this.serviceName = builder.serviceName;
        this.title = Objects.requireNonNull(builder.title, "title must not be null");
        this.description = Objects.requireNonNull(builder.description, "description must not be null");
        this.port = builder.port;
        this.schemes = Map.copyOf(builder.schemes);
        this.pathErrorRules = List.copyOf(builder.pathErrorRules);
    }

    public static Builder builder(String serviceName, int port) {
        return new Builder(serviceName, port);
    }

    public String serviceName() {
        return serviceName;
    }

    // springdoc이 컨트롤러를 읽어 채우기 전의 기본 문서(정보·로컬 서버·보안 스킴)
    public OpenAPI baseOpenApi() {
        Components components = new Components();
        schemes.values().stream()
                .sorted((left, right) -> left.name().compareTo(right.name()))
                .forEach(scheme -> components.addSecuritySchemes(scheme.name(), scheme.scheme()));
        return new OpenAPI()
                .info(new Info().title(title).version("v1").description(description))
                .addServersItem(new Server()
                        .url("http://localhost:" + port)
                        .description(serviceName + " 로컬 실행 기본 포트(`./scripts/internal/run-local-service.sh " + serviceName + "`)"))
                .components(components);
    }

    Map<String, SchemeDefinition> schemes() {
        return schemes;
    }

    List<PathErrorRule> pathErrorRules() {
        return pathErrorRules;
    }

    // 보안 스킴과, 그 스킴이 붙은 API에 공통으로 생기는 인증 실패 에러.
    // urlPatterns가 있으면 그 패턴에 걸리는 경로에 스킴을 자동으로 붙인다(내부 토큰 필터처럼 경로로 보호하는 경우).
    record SchemeDefinition(String name, SecurityScheme scheme, List<String> urlPatterns, List<DocumentedError> failures) {
    }

    // 경로 조건에 맞는 API에 공통으로 생기는 에러(예: 요청 한도 초과)
    record PathErrorRule(Predicate<String> paths, List<DocumentedError> errors) {
    }

    // 서비스 이름·포트·제목·설명·보안 스킴·경로별 공통 에러를 모아 MockcoinApiDocs를 만드는 빌더
    public static final class Builder {
        private final String serviceName;
        private final int port;
        private final Map<String, SchemeDefinition> schemes = new LinkedHashMap<>();
        private final List<PathErrorRule> pathErrorRules = new ArrayList<>();
        private String title;
        private String description;

        // 서비스 이름(필수)과 로컬 기본 포트를 받아 빌더를 만든다.
        private Builder(String serviceName, int port) {
            this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
            this.port = port;
        }

        // 문서 제목
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        // 문서 첫 화면에 보이는 서비스 설명(Markdown)
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        // Authorization: Bearer <access token> 인증. API에는 @SecurityRequirement(name = BEARER_AUTH)로 붙인다.
        // 인증 필터는 실패 원인과 관계없이 failureStatus로 응답하므로(errorCode·reason은 예외 그대로) 그 상태로 문서화한다.
        public Builder bearerToken(String description, int failureStatus, ErrorCode... failures) {
            return scheme(BEARER_AUTH, bearerScheme(description), List.of(), errors(failures).stream()
                    .map(error -> error.renderedAs(failureStatus))
                    .toList());
        }

        // Authorization: Bearer <access token> 인증. 실패 에러는 각 정의의 상태 코드 그대로 문서화한다.
        public Builder bearerToken(String description, ErrorCode... failures) {
            return scheme(BEARER_AUTH, bearerScheme(description), List.of(), errors(failures));
        }

        // 요청 헤더 값으로 인증한다. API에는 @SecurityRequirement(name = headerName)로 붙인다.
        public Builder headerToken(String headerName, String description, ErrorCode... failures) {
            return scheme(headerName, headerScheme(headerName, description), List.of(), errors(failures));
        }

        // 쿠키 값으로 인증한다. API에는 @SecurityRequirement(name = cookieName)로 붙인다.
        public Builder cookieSession(String cookieName, String description, ErrorCode... failures) {
            return scheme(cookieName, new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.COOKIE)
                    .name(cookieName)
                    .description(description), List.of(), errors(failures));
        }

        // 서비스 간 내부 토큰 필터. 필터의 헤더 이름·URL 패턴을 그대로 읽어 걸리는 API에 보안 스킴과 인증 실패 에러를 붙인다.
        public Builder internalTokenFilter(FilterRegistrationBean<InternalTokenAuthenticationFilter> registration, String description) {
            InternalTokenAuthenticationFilter filter = Objects.requireNonNull(registration.getFilter(), "filter must not be null");
            Collection<String> urlPatterns = registration.getUrlPatterns();
            if (urlPatterns.isEmpty()) {
                throw new IllegalArgumentException("내부 토큰 필터에 URL 패턴이 없습니다: " + filter.headerName());
            }
            return scheme(filter.headerName(), headerScheme(filter.headerName(), description), List.copyOf(urlPatterns), List.of(
                    DocumentedError.of(CommonErrorCode.INTERNAL_TOKEN_REJECTED),
                    new DocumentedError(CommonErrorCode.INTERNAL_TOKEN_NOT_CONFIGURED, filter.notConfiguredReason())
            ));
        }

        // 경로 조건에 맞는 모든 API에 공통 에러를 붙인다
        public Builder errorsForPaths(Predicate<String> paths, ErrorCode... errors) {
            pathErrorRules.add(new PathErrorRule(Objects.requireNonNull(paths, "paths must not be null"), errors(errors)));
            return this;
        }

        public MockcoinApiDocs build() {
            return new MockcoinApiDocs(this);
        }

        // 보안 스킴을 등록하고, 같은 이름이 이미 있으면 IllegalArgumentException을 던진다.
        private Builder scheme(String name, SecurityScheme scheme, List<String> urlPatterns, List<DocumentedError> failures) {
            if (schemes.containsKey(name)) {
                throw new IllegalArgumentException("보안 스킴 이름이 중복됩니다: " + name);
            }
            schemes.put(name, new SchemeDefinition(name, scheme, urlPatterns, failures));
            return this;
        }

        // JWT Bearer 형식의 HTTP 보안 스킴을 만든다.
        private static SecurityScheme bearerScheme(String description) {
            return new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description(description);
        }

        // 지정한 요청 헤더로 값을 받는 API 키 보안 스킴을 만든다.
        private static SecurityScheme headerScheme(String headerName, String description) {
            return new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name(headerName)
                    .description(description);
        }

        // 에러 정의들을 기본 사유·상태 코드의 문서 에러 목록으로 바꾼다.
        private static List<DocumentedError> errors(ErrorCode... definitions) {
            return java.util.Arrays.stream(definitions).map(DocumentedError::of).toList();
        }
    }
}
