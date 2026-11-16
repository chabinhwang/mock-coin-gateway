package com.mockcoin.gateway.global.config;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.global.error.GatewayErrorCode;
import com.mockcoin.shared.api.CommonErrorCode;
import com.mockcoin.shared.apidocs.MockcoinApiDocs;
import com.mockcoin.shared.apidocs.MockcoinApiDocsConfiguration;
import com.mockcoin.shared.security.InternalTokenAuthenticationFilter;
import com.mockcoin.shared.service.ServicePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

// gateway-service API 문서(OpenAPI) 정의. springdoc.api-docs.enabled=true(로컬 프로필·문서 생성 테스트)일 때만 켜진다.
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
@Import(MockcoinApiDocsConfiguration.class)
public class GatewayApiDocsConfiguration {
    private static final String DESCRIPTION = """
            외부(모바일 앱·관리자 브라우저)에서 들어오는 요청의 유일한 입구다. 자체 데이터는 거의 없고, 요청을 하위 서비스로 넘기며
            인증 확인·요청 한도·보안 헤더를 맡는다. 관리자 콘솔 화면과 그 API(여러 서비스 관리 API를 모은 facade)도 여기서 제공한다.

            ### 누가 부르나
            - **모바일 앱**: `/api/v1/**`(이 문서의 '프록시' API)와 실시간 WebSocket `/ws`.
            - **관리자 브라우저**: `/admin/**`(관리자 기능을 켠 환경만). 화면은 정적 파일이고 API는 `/admin/api/**`다.
            - **market-service·trading-service**: 앱 WebSocket 구독자에게 보낼 이벤트를 `/internal/gateway/events/**`로 넘긴다.

            ### 용어
            - **프록시(전달)**: 경로·쿼리·본문을 바꾸지 않고 하위 서비스로 넘기고, 하위 응답의 상태 코드·헤더·본문을 그대로 돌려주는 것. 요청·응답 형식과 업무 에러는 하위 서비스 문서를 본다.
            - **access token 검증**: trading·community 요청은 게이트웨이가 토큰에서 사용자 ID를 얻어 `X-Mockcoin-User-Id`로 넣어 준다. 직접 검증 설정과 공유 폐기 목록(denylist)이 모두 켜져 있으면 공유 서명 키로 직접 검증하고, 아니면 user-service `GET /api/v1/auth/me`에 묻는다(공유 폐기 목록이 켜진 환경에서만 그 결과를 잠시 캐시한다).
            - **관리자 facade**: `/admin/api/v1/admin-console/**`. 관리자 인증을 확인한 뒤 user·trading·community-service의 `/internal/**/admin/**` API를 서비스별 내부 토큰으로 부른다.

            ### 공통 규칙
            - 게이트웨이가 직접 만드는 응답은 `{resultType, success, error}` 봉투다. 프록시 응답은 하위 서비스 응답 그대로다.
            - `/api/**`와 `/admin/api/**`에는 요청 한도가 있다(IP별 분당 한도와, 로그인·주문·결제·관리자 쓰기 등 경로별 한도). 넘으면 429와 `Retry-After`·`X-RateLimit-*` 헤더를 준다.
            - 관리자 기능이 꺼진 환경(`mockcoin.admin.enabled=false`, 기본)에서는 `/admin/**`가 모두 404이고 이 문서의 관리자 API가 없다.

            ### 이 문서에 없는 것
            앱 실시간 WebSocket `/ws`(구독 메시지 처리는 `com.mockcoin.gateway.websocket.service.GatewayWebSocketHandler`)와
            market-service가 시세 이벤트를 넣는 내부 WebSocket `/internal/gateway/events/public/ws`(`X-Mockcoin-Internal-Event-Token` 필요)는 OpenAPI로 표현하지 않는다.""";

    // gateway OpenAPI 문서를 구성한다(Bearer·관리자 토큰·세션 쿠키 인증, 내부 이벤트 토큰, /api·관리자 API 요청 한도 에러).
    @Bean
    MockcoinApiDocs gatewayApiDocs(
            FilterRegistrationBean<InternalTokenAuthenticationFilter> gatewayInternalEventTokenFilter,
            GatewayAdminProperties adminProperties
    ) {
        String adminApiPrefix = adminProperties.basePath() + "/api/";
        return MockcoinApiDocs.builder("gateway-service", ServicePort.GATEWAY.port())
                .title("gateway-service API")
                .description(DESCRIPTION)
                .bearerToken("""
                        user-service가 발급한 access token. `Authorization: Bearer <access token>`으로 보낸다.
                        trading·community-service로 가는 요청은 게이트웨이가 이 토큰을 검증해 사용자 ID로 바꿔 넘긴다.
                        user-service로 가는 요청은 헤더를 그대로 넘기므로 그쪽 문서의 인증 규칙을 따른다.""",
                        GatewayErrorCode.ACCESS_TOKEN_REJECTED,
                        GatewayErrorCode.AUTH_SERVICE_UNAVAILABLE,
                        GatewayErrorCode.AUTH_SERVICE_INVALID_RESPONSE)
                .headerToken(GatewayAdminProperties.ADMIN_TOKEN_HEADER, """
                        관리자 facade를 프로그램으로 부를 때 쓰는 공유 토큰(`GATEWAY_ADMIN_INTERNAL_TOKEN`). 이 헤더나 관리자 세션 쿠키 중 하나만 있으면 된다.
                        토큰으로 부르면 변경 요청의 같은 출처(CSRF) 검사를 하지 않는다.""",
                        GatewayErrorCode.ADMIN_AUTH_REQUIRED,
                        GatewayErrorCode.ADMIN_TOKEN_NOT_CONFIGURED)
                .cookieSession(GatewayAdminProperties.SESSION_COOKIE_NAME, """
                        관리자 로그인(`/admin/api/auth/**`)으로 받는 세션 쿠키(30분). 패스키 등록 전용 세션으로는 facade를 쓸 수 없다.
                        쿠키로 변경 요청을 보내면 `Origin`(없으면 `Referer`)이 게이트웨이와 같은 출처여야 한다.""",
                        GatewayErrorCode.ADMIN_AUTH_REQUIRED,
                        GatewayErrorCode.ADMIN_TOKEN_NOT_CONFIGURED)
                .internalTokenFilter(gatewayInternalEventTokenFilter, """
                        서비스 간 이벤트 전달용 공유 토큰(`mockcoin.websocket.internal-event-token`, 환경변수 `GATEWAY_INTERNAL_EVENT_TOKEN`).
                        market-service·trading-service가 같은 값을 보낸다. 값이 설정되지 않으면 모든 이벤트 요청을 401로 거부한다.""")
                .errorsForPaths(path -> path.startsWith("/api/") || path.startsWith(adminApiPrefix),
                        CommonErrorCode.RATE_LIMIT_EXCEEDED)
                .build();
    }
}
