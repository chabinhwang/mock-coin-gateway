package com.mockcoin.gateway.proxy.controller;

import com.mockcoin.gateway.global.security.GatewayTokenAuthenticator;
import com.mockcoin.gateway.proxy.service.GatewayProxyService;
import com.mockcoin.shared.apidocs.MockcoinApiDocs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// /api/v1/** 외부 REST 요청을 user/trading/market 서비스로 프록시한다
@RestController
@RequestMapping("/api/v1")
public class GatewayProxyController {
    private final GatewayProxyService proxyService;
    private final GatewayTokenAuthenticator tokenAuthenticator;

    // 프록시 서비스와 토큰 인증기를 주입받는다.
    public GatewayProxyController(GatewayProxyService proxyService, GatewayTokenAuthenticator tokenAuthenticator) {
        this.proxyService = proxyService;
        this.tokenAuthenticator = tokenAuthenticator;
    }

    // 로그인/리프레시/로그아웃 등 auth POST 요청을 user-service로 프록시한다
    @Tag(name = "프록시: user-service", description = "앱의 로그인·프로필·알림 동의·결제·초기화권·자산 초기화 요청을 user-service로 넘긴다. `Authorization` 헤더를 그대로 넘기고 토큰 검증은 user-service가 한다.")
    @Operation(summary = "인증 요청 전달(POST)", description = """
            user-service `POST /api/v1/auth/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다. `logout` 요청이 2xx로 끝나면 이 게이트웨이 인스턴스에 캐시한 그 토큰의 인증 결과를 지운다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @PostMapping("/auth/{*path}")
    public void postAuth(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "toss/exchange") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        String suffix = strip(path);
        HttpStatusCode status = proxyService.proxyToUser(
                HttpMethod.POST, "/api/v1/auth/" + suffix, request, response, true);
        if (status.is2xxSuccessful() && "logout".equals(suffix)) {
            tokenAuthenticator.invalidate(request.getHeader(HttpHeaders.AUTHORIZATION));
        }
    }

    // 현재 사용자 조회 등 auth GET 요청을 user-service로 프록시한다
    @Tag(name = "프록시: user-service")
    @Operation(summary = "인증 조회 전달(GET)", description = """
            user-service `GET /api/v1/auth/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @GetMapping("/auth/{*path}")
    public void getAuth(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "me") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(HttpMethod.GET, "/api/v1/auth/" + strip(path), request, response, false);
    }

    // 내 프로필 조회 요청을 user-service로 프록시한다
    @Tag(name = "프록시: user-service")
    @Operation(summary = "내 프로필 조회 전달", description = """
            user-service `GET /api/v1/me/profile`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @GetMapping("/me/profile")
    public void getProfile(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(HttpMethod.GET, "/api/v1/me/profile", request, response, false);
    }

    // 프로필 변경(닉네임 등) 요청을 user-service로 프록시한다
    @Tag(name = "프록시: user-service")
    @Operation(summary = "프로필 변경 전달", description = """
            user-service `POST /api/v1/me/profile/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @PostMapping("/me/profile/{*path}")
    public void postProfile(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "nickname") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(HttpMethod.POST, "/api/v1/me/profile/" + strip(path), request, response, true);
    }

    // 알림 동의 조회 요청을 user-service로 프록시한다.
    @Tag(name = "프록시: user-service")
    @Operation(summary = "알림 동의 조회 전달", description = """
            user-service `GET /api/v1/me/notification-agreements`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @GetMapping("/me/notification-agreements")
    public void getNotificationAgreements(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(HttpMethod.GET, "/api/v1/me/notification-agreements", request, response, false);
    }

    // 알림 동의 변경 요청을 user-service로 프록시한다.
    @Tag(name = "프록시: user-service")
    @Operation(summary = "알림 동의 변경 전달", description = """
            user-service `PUT /api/v1/me/notification-agreements`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @PutMapping("/me/notification-agreements")
    public void putNotificationAgreements(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(HttpMethod.PUT, "/api/v1/me/notification-agreements", request, response, true);
    }

    // billing 조회 요청(상품 목록 등)을 user-service로 프록시한다
    @Tag(name = "프록시: user-service")
    @Operation(summary = "결제 조회 전달", description = """
            user-service `GET /api/v1/billing/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @GetMapping("/billing/{*path}")
    public void getBilling(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "reset-products") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(HttpMethod.GET, "/api/v1/billing/" + strip(path), request, response, false);
    }

    // IAP 결제/지급/검증 등 billing POST 요청을 user-service로 프록시한다
    @Tag(name = "프록시: user-service")
    @Operation(summary = "결제 요청 전달", description = """
            user-service `POST /api/v1/billing/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @PostMapping("/billing/{*path}")
    public void postBilling(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "iap/grant") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(HttpMethod.POST, "/api/v1/billing/" + strip(path), request, response, true);
    }

    // 내 초기화권 보유 수량 조회를 user-service로 프록시한다
    @Tag(name = "프록시: user-service")
    @Operation(summary = "초기화권 보유 조회 전달", description = """
            user-service `GET /api/v1/me/reset-passes`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @GetMapping("/me/reset-passes")
    public void getResetPasses(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(HttpMethod.GET, "/api/v1/me/reset-passes", request, response, false);
    }

    // 초기화권 구매 이력 조회를 user-service로 프록시한다
    @Tag(name = "프록시: user-service")
    @Operation(summary = "초기화권 구매 내역 전달", description = """
            user-service `GET /api/v1/me/reset-pass-purchases`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @GetMapping("/me/reset-pass-purchases")
    public void getResetPassPurchases(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(HttpMethod.GET, "/api/v1/me/reset-pass-purchases", request, response, false);
    }

    // 초기화권 환불 요청을 user-service로 프록시한다
    @Tag(name = "프록시: user-service")
    @Operation(summary = "초기화권 구매 환불 전달", description = """
            user-service `POST /api/v1/me/reset-pass-purchases/{purchaseId}/refund`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @PostMapping("/me/reset-pass-purchases/{purchaseId}/refund")
    public void refundResetPassPurchase(@Parameter(description = "구매 ID(검증 없이 하위 경로에 그대로 붙인다)", example = "15") @PathVariable String purchaseId, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(
                HttpMethod.POST, "/api/v1/me/reset-pass-purchases/" + purchaseId + "/refund", request, response, false);
    }

    // 자산 초기화 요청을 user-service로 프록시한다(내부적으로 trading-service 호출 포함)
    @Tag(name = "프록시: user-service")
    @Operation(summary = "자산 초기화 전달", description = """
            user-service `POST /api/v1/me/reset`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. `Authorization` 헤더를 그대로 넘기고, 신뢰할 수 있는 클라이언트 IP를 `X-Forwarded-For`로 넣는다. 토큰 검증과 인증 에러는 user-service가 한다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @PostMapping("/me/reset")
    public void resetMe(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToUser(HttpMethod.POST, "/api/v1/me/reset", request, response, true);
    }

    // 마켓 메타데이터 목록 조회를 market-service로 프록시한다
    @Tag(name = "프록시: market-service", description = "앱의 시세·캔들·아이콘 조회를 market-service로 넘긴다. 로그인이 필요 없다.")
    @Operation(summary = "마켓 목록 전달", description = """
            market-service `GET /api/v1/markets`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 로그인 없이 넘긴다(`Authorization`은 넘기지 않는다). 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @GetMapping("/markets")
    public void getMarketsRoot(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToMarket(HttpMethod.GET, "/api/v1/markets", request, response);
    }

    // 종목 상세/캔들/스냅샷 등 market 하위 경로를 market-service로 프록시한다
    @Tag(name = "프록시: market-service")
    @Operation(summary = "마켓 하위 조회 전달", description = """
            market-service `GET /api/v1/markets/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 로그인 없이 넘긴다(`Authorization`은 넘기지 않는다). 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다. 아이콘 이미지(`{market}/icon`)처럼 JSON이 아닌 응답도 그대로 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @GetMapping("/markets/{*path}")
    public void getMarkets(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "KRW-BTC/candles") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToMarket(HttpMethod.GET, "/api/v1/markets/" + strip(path), request, response);
    }

    // 내 포트폴리오 조회를 인증 후 trading-service로 프록시한다
    @Tag(name = "프록시: trading-service", description = "앱의 포트폴리오·주문·랭킹 요청을 trading-service로 넘긴다. 로그인이 필요한 요청은 게이트웨이가 access token을 검증해 `X-Mockcoin-User-Id`로 바꿔 넘긴다.")
    @Operation(summary = "포트폴리오 조회 전달", description = """
            trading-service `GET /api/v1/portfolio`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 게이트웨이가 `Authorization: Bearer` access token을 검증해 사용자 ID를 얻고 `X-Mockcoin-User-Id` 헤더로 넣어 넘긴다. 클라이언트가 보낸 `X-Mockcoin-User-Id`와 `Authorization`은 넘기지 않는다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @SecurityRequirement(name = MockcoinApiDocs.BEARER_AUTH)
    @GetMapping("/portfolio")
    public void getPortfolio(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToTrading(HttpMethod.GET, "/api/v1/portfolio", request, response, false);
    }

    // 주문 생성 요청을 인증 후 trading-service로 프록시한다
    @Tag(name = "프록시: trading-service")
    @Operation(summary = "주문 생성 전달", description = """
            trading-service `POST /api/v1/orders`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 게이트웨이가 `Authorization: Bearer` access token을 검증해 사용자 ID를 얻고 `X-Mockcoin-User-Id` 헤더로 넣어 넘긴다. 클라이언트가 보낸 `X-Mockcoin-User-Id`와 `Authorization`은 넘기지 않는다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @SecurityRequirement(name = MockcoinApiDocs.BEARER_AUTH)
    @PostMapping("/orders")
    public void postOrder(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToTrading(HttpMethod.POST, "/api/v1/orders", request, response, true);
    }

    // 내 주문 목록 조회를 인증 후 trading-service로 프록시한다
    @Tag(name = "프록시: trading-service")
    @Operation(summary = "주문 목록 전달", description = """
            trading-service `GET /api/v1/orders`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 게이트웨이가 `Authorization: Bearer` access token을 검증해 사용자 ID를 얻고 `X-Mockcoin-User-Id` 헤더로 넣어 넘긴다. 클라이언트가 보낸 `X-Mockcoin-User-Id`와 `Authorization`은 넘기지 않는다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @SecurityRequirement(name = MockcoinApiDocs.BEARER_AUTH)
    @GetMapping("/orders")
    public void getOrders(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToTrading(HttpMethod.GET, "/api/v1/orders", request, response, false);
    }

    // 주문 취소 요청을 인증 후 trading-service로 프록시한다
    @Tag(name = "프록시: trading-service")
    @Operation(summary = "주문 취소 전달", description = """
            trading-service `POST /api/v1/orders/{orderId}/cancel`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 게이트웨이가 `Authorization: Bearer` access token을 검증해 사용자 ID를 얻고 `X-Mockcoin-User-Id` 헤더로 넣어 넘긴다. 클라이언트가 보낸 `X-Mockcoin-User-Id`와 `Authorization`은 넘기지 않는다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @SecurityRequirement(name = MockcoinApiDocs.BEARER_AUTH)
    @PostMapping("/orders/{orderId}/cancel")
    public void cancelOrder(@Parameter(description = "주문 ID(검증 없이 하위 경로에 그대로 붙인다)", example = "123") @PathVariable String orderId, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToTrading(HttpMethod.POST, "/api/v1/orders/" + orderId + "/cancel", request, response, false);
    }

    // 내 랭킹 조회를 인증 후 trading-service로 프록시한다
    @Tag(name = "프록시: trading-service")
    @Operation(summary = "내 순위 전달", description = """
            trading-service `GET /api/v1/rankings/me`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 게이트웨이가 `Authorization: Bearer` access token을 검증해 사용자 ID를 얻고 `X-Mockcoin-User-Id` 헤더로 넣어 넘긴다. 클라이언트가 보낸 `X-Mockcoin-User-Id`와 `Authorization`은 넘기지 않는다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @SecurityRequirement(name = MockcoinApiDocs.BEARER_AUTH)
    @GetMapping("/rankings/me")
    public void getMyRanking(HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToTrading(HttpMethod.GET, "/api/v1/rankings/me", request, response, false);
    }

    // 시즌/보드 등 공개 랭킹 조회를 인증 없이 trading-service로 프록시한다
    @Tag(name = "프록시: trading-service")
    @Operation(summary = "랭킹 공개 조회 전달", description = """
            trading-service `GET /api/v1/rankings/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 로그인 없이 넘긴다(`Authorization`은 넘기지 않는다). 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @GetMapping("/rankings/{*path}")
    public void getRankings(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "seasons/current/board") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToTradingPublic(HttpMethod.GET, "/api/v1/rankings/" + strip(path), request, response);
    }

    // 커뮤니티 조회 요청을 인증 후 community-service로 프록시한다.
    @Tag(name = "프록시: community-service", description = "앱의 커뮤니티 요청을 community-service로 넘긴다. 게이트웨이가 access token을 검증해 `X-Mockcoin-User-Id`로 바꿔 넘긴다(모두 로그인 필요).")
    @Operation(summary = "커뮤니티 조회 전달(GET)", description = """
            community-service `GET /api/v1/community/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 게이트웨이가 `Authorization: Bearer` access token을 검증해 사용자 ID를 얻고 `X-Mockcoin-User-Id` 헤더로 넣어 넘긴다. 클라이언트가 보낸 `X-Mockcoin-User-Id`와 `Authorization`은 넘기지 않는다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @SecurityRequirement(name = MockcoinApiDocs.BEARER_AUTH)
    @GetMapping("/community/{*path}")
    public void getCommunity(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "posts") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToCommunity(HttpMethod.GET, "/api/v1/community/" + strip(path), request, response, false);
    }

    // 커뮤니티 POST 요청을 인증 후 community-service로 프록시한다.
    @Tag(name = "프록시: community-service")
    @Operation(summary = "커뮤니티 요청 전달(POST)", description = """
            community-service `POST /api/v1/community/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 게이트웨이가 `Authorization: Bearer` access token을 검증해 사용자 ID를 얻고 `X-Mockcoin-User-Id` 헤더로 넣어 넘긴다. 클라이언트가 보낸 `X-Mockcoin-User-Id`와 `Authorization`은 넘기지 않는다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @SecurityRequirement(name = MockcoinApiDocs.BEARER_AUTH)
    @PostMapping("/community/{*path}")
    public void postCommunity(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "posts") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToCommunity(HttpMethod.POST, "/api/v1/community/" + strip(path), request, response, true);
    }

    // 커뮤니티 수정 요청을 인증 후 community-service로 프록시한다.
    @Tag(name = "프록시: community-service")
    @Operation(summary = "커뮤니티 수정 전달(PATCH)", description = """
            community-service `PATCH /api/v1/community/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 게이트웨이가 `Authorization: Bearer` access token을 검증해 사용자 ID를 얻고 `X-Mockcoin-User-Id` 헤더로 넣어 넘긴다. 클라이언트가 보낸 `X-Mockcoin-User-Id`와 `Authorization`은 넘기지 않는다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @SecurityRequirement(name = MockcoinApiDocs.BEARER_AUTH)
    @PatchMapping("/community/{*path}")
    public void patchCommunity(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "posts/101") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToCommunity(HttpMethod.PATCH, "/api/v1/community/" + strip(path), request, response, true);
    }

    // 커뮤니티 삭제 요청을 인증 후 community-service로 프록시한다.
    @Tag(name = "프록시: community-service")
    @Operation(summary = "커뮤니티 삭제 전달(DELETE)", description = """
            community-service `DELETE /api/v1/community/{path}`로 그대로 넘기고 하위 응답(상태 코드·헤더·본문)을 그대로 돌려준다. 요청·응답 형식과 업무 에러는 하위 서비스 문서의 같은 경로 API를 본다. 게이트웨이가 `Authorization: Bearer` access token을 검증해 사용자 ID를 얻고 `X-Mockcoin-User-Id` 헤더로 넣어 넘긴다. 클라이언트가 보낸 `X-Mockcoin-User-Id`와 `Authorization`은 넘기지 않는다. 쿼리 문자열은 그대로 붙이고, 그 밖의 요청 헤더는 `Content-Type`·`Accept`·`X-Requested-With`·`X-Mockcoin-Client-Order-Id`·`traceparent`·`tracestate`만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "하위 서비스 응답을 상태 코드까지 그대로 전달한다(2xx가 아닐 수도 있다).",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "object", description = "하위 서비스 응답 본문 그대로")))
    @SecurityRequirement(name = MockcoinApiDocs.BEARER_AUTH)
    @DeleteMapping("/community/{*path}")
    public void deleteCommunity(@Parameter(description = "하위 경로(슬래시 포함 여러 단계 가능). 앞의 `/`를 떼고 하위 서비스 경로 뒤에 붙인다.", example = "posts/101") @PathVariable String path, HttpServletRequest request, HttpServletResponse response) {
        proxyService.proxyToCommunity(HttpMethod.DELETE, "/api/v1/community/" + strip(path), request, response, false);
    }

    // 경로 앞의 '/'를 떼어 낸다.
    private static String strip(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
