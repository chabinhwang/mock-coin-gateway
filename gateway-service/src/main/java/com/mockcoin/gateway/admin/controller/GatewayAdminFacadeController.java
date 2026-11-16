package com.mockcoin.gateway.admin.controller;

import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.node.ObjectNode;
import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.service.GatewayAdminCsrfGuard;
import com.mockcoin.gateway.admin.service.AdminQueryString;
import com.mockcoin.gateway.admin.service.GatewayAdminFacadeService;
import com.mockcoin.gateway.global.error.GatewayApiErrors;
import com.mockcoin.gateway.global.error.GatewayErrorCode;
import com.mockcoin.shared.api.ApiResponse;
import com.mockcoin.shared.api.CommonApiErrors;
import com.mockcoin.shared.api.CommonErrorCode;
import com.mockcoin.shared.api.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

// admin 콘솔 API를 user/trading-service 내부 admin endpoint로 라우팅하는 facade 컨트롤러
@RestController
@ConditionalOnProperty(prefix = "mockcoin.admin", name = "enabled", havingValue = "true")
@RequestMapping("${mockcoin.admin.base-path:/admin}/api/v1/admin-console")
@SecurityRequirement(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER)
@SecurityRequirement(name = GatewayAdminProperties.SESSION_COOKIE_NAME)
public class GatewayAdminFacadeController {
    private static final Set<String> REPORT_TARGET_TYPES = Set.of("post", "comment");

    private final GatewayAdminFacadeService facadeService;
    private final GatewayAdminCsrfGuard csrfGuard;

    // 관리자 facade 서비스와 CSRF 검사기를 주입받는다.
    public GatewayAdminFacadeController(GatewayAdminFacadeService facadeService, GatewayAdminCsrfGuard csrfGuard) {
        this.facadeService = facadeService;
        this.csrfGuard = csrfGuard;
    }

    // billing 상품 목록 조회를 user-service admin endpoint로 프록시한다
    @Tag(name = "관리자 콘솔: 사용자·결제", description = "사용자·지갑·결제 상품·결제 주문·초기화권·IAP를 조회하고 조치한다. 대부분 user-service·trading-service 관리 API로 그대로 전달한다.")
    @Operation(summary = "결제 상품 목록", description = """
            user-service `GET /internal/user-service/admin/billing/products`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 user-service 문서의 해당 API를 본다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service `GET /internal/user-service/admin/billing/products` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/billing/products")
    public ResponseEntity<byte[]> billingProducts(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token) {
        facadeService.verifyGatewayAdmin(token);
        return facadeService.user(HttpMethod.GET, "/internal/user-service/admin/billing/products", null);
    }

    // user/trading/market/ingestor overview fragment를 병렬 조회해 합쳐 반환한다
    @Tag(name = "관리자 콘솔: 대시보드", description = "관리자 콘솔 첫 화면의 현황. 여러 서비스의 관리 API를 한 번에 모아 준다.")
    @Operation(summary = "대시보드 현황", description = """
            user-service(필수)와 trading·market·ingestor·community-service(선택)의 관리 현황을 동시에 조회해 하나로 합친다. user-service 현황을 못 받으면 실패(502)하고, 선택 서비스가 실패하면 그 부분을 비운 채 `degradedServices`에 서비스 이름을 넣는다 (market·ingestor 실패는 목록에 넣지 않는다). 최근 가입 사용자 행에는 trading-service 지갑을 붙인다. 읽기 전용이다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "대시보드 현황 객체. `runtime`(auth·billing=user-service, order=trading-service, security·websocket=gateway 설정, integrations=각 서비스 연동 설정을 합친 것), `operatorNotes`(운영 메모 `[{title, detail}]`), `billingProducts`·`users`·`paymentOrders`·`billingProductTypes`(user-service 현황; `users` 행에는 `hasWallet`·`seedKrwBalance`·`krwBalance`를 붙임), `rankingSeasons`·`activeSeasonId`·`activeSeasonRankings`(trading-service 현황; 순위 행에 `nickname`을 붙임)·`seasonStatuses`, `counts`(user·trading·community 건수 필드를 한 객체로 합침), `degradedServices`(일부 실패한 서비스 이름 배열). 각 하위 값의 모양은 해당 서비스의 `GET .../admin/overview` 문서를 본다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_OVERVIEW_FAILED})
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<ObjectNode>> overview(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token) {
        facadeService.verifyGatewayAdmin(token);
        return ResponseEntity.ok(ApiResponse.success(facadeService.overview()));
    }

    // 신규 billing 상품 생성을 user-service admin endpoint로 프록시한다
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "결제 상품 등록", description = """
            user-service `POST /internal/user-service/admin/billing/products`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 user-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service `POST /internal/user-service/admin/billing/products` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/billing/products")
    public ResponseEntity<byte[]> createBillingProduct(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token, @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "user-service `AdminProductRequest`와 같은 JSON(`providerSku`, `productType`, `grantAmount`, `active`). 그대로 하위 API로 넘긴다.", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", example = "{\"providerSku\": \"mockcoin.reset_pass.1\", \"productType\": \"RESET_PASS\", \"grantAmount\": 1, \"active\": true}"))) @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.user(HttpMethod.POST, "/internal/user-service/admin/billing/products", body);
    }

    // 기존 billing 상품 수정을 user-service admin endpoint로 프록시한다
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "결제 상품 수정", description = """
            user-service `PUT /internal/user-service/admin/billing/products/{productId}`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 user-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service `PUT /internal/user-service/admin/billing/products/{productId}` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PutMapping("/billing/products/{productId}")
    public ResponseEntity<byte[]> updateBillingProduct(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token, @Parameter(description = "결제 상품 ID", example = "1") @PathVariable Long productId, @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "user-service `AdminProductRequest`와 같은 JSON. 그대로 하위 API로 넘긴다.", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", example = "{\"providerSku\": \"mockcoin.reset_pass.1\", \"productType\": \"RESET_PASS\", \"grantAmount\": 1, \"active\": false}"))) @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.user(HttpMethod.PUT, "/internal/user-service/admin/billing/products/" + productId, body);
    }

    // 관리자 권한으로 사용자에게 초기화권을 지급한다
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "초기화권 직접 지급", description = """
            user-service `POST /internal/user-service/admin/users/{userId}/reset-passes`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 user-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service `POST /internal/user-service/admin/users/{userId}/reset-passes` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/users/{userId}/reset-passes")
    public ResponseEntity<byte[]> grantResetPass(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token, @Parameter(description = "사용자 ID", example = "42") @PathVariable Long userId, @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "user-service `AdminResetPassGrantRequest`와 같은 JSON(`quantity`, 선택 `expiresAt`). 그대로 하위 API로 넘긴다.", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", example = "{\"quantity\": 1}"))) @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.user(HttpMethod.POST, "/internal/user-service/admin/users/" + userId + "/reset-passes", body);
    }

    // 관리자 지급한 초기화권을 전체 또는 지정 수량만큼 회수한다(구매분은 환불 흐름 사용)
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "초기화권 회수", description = """
            user-service `POST /internal/user-service/admin/reset-passes/{passId}/revoke`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 user-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service `POST /internal/user-service/admin/reset-passes/{passId}/revoke` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/reset-passes/{passId}/revoke")
    public ResponseEntity<byte[]> revokeResetPass(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token, @Parameter(description = "초기화권 원장 ID", example = "7") @PathVariable Long passId, @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "user-service `AdminResetPassRevokeRequest`와 같은 JSON(선택 `quantity`; 생략하면 남은 수량 전부). 그대로 하위 API로 넘긴다.", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", example = "{\"quantity\": 1}"))) @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.user(HttpMethod.POST, "/internal/user-service/admin/reset-passes/" + passId + "/revoke", body);
    }

    // 사용자 검색(userId·tossUserKey 일치, 닉네임 부분 일치) 페이지에 해당 사용자들의 지갑을 붙여 반환한다
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "사용자 검색", description = """
            user-service 사용자 검색 결과 한 페이지에, 그 페이지 사용자들의 trading-service 지갑만 골라 붙인다. 지갑 조회가 실패해도 목록은 주고 `walletLookupFailed=true`, 각 행 `hasWallet=null`로 알린다. 읽기 전용이다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service 사용자 검색 응답의 `success` 객체에 다음을 더한 것: 각 `items[]` 행의 `hasWallet`(지갑 유무, 조회 실패면 `null`)·`seedKrwBalance`·`krwBalance`(지갑 없으면 `null`), 최상위 `walletLookupFailed`(지갑 조회 실패 여부).")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_ERROR, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<ObjectNode>> users(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "검색어. 하위 API로 그대로 넘긴다(사용자 ID·Toss 사용자 키 일치, 닉네임 부분 일치).", example = "용감한") @RequestParam(name = "q", required = false) String query,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        return ResponseEntity.ok(ApiResponse.success(facadeService.queries().users(query, cursor, limit)));
    }

    // 사용자 상세: 계정 요약 + 지갑·보유 종목
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "사용자 상세", description = """
            user-service 사용자 상세와 trading-service 지갑·보유 종목을 함께 준다. 사용자가 없으면 user-service의 404를 그대로 준다. 지갑·보유 종목 조회가 실패하면 `portfolio=null`, `portfolioUnavailable=true`로 답한다. 읽기 전용이다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "`{user, portfolio, portfolioUnavailable}`. `user`=user-service 사용자 상세의 `success`, `portfolio`=trading-service 사용자 지갑·보유 종목의 `success`(조회 실패면 `null`), `portfolioUnavailable`=지갑 조회 실패 여부.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_ERROR, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<ObjectNode>> userDetail(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "사용자 ID", example = "42") @PathVariable Long userId
    ) {
        facadeService.verifyGatewayAdmin(token);
        return ResponseEntity.ok(ApiResponse.success(facadeService.queries().userDetail(userId)));
    }

    // 결제 주문을 사용자·상태로 걸러 최신순으로 조회한다
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "결제 주문 목록", description = """
            user-service `GET /internal/user-service/admin/payment-orders`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 user-service 문서의 해당 API를 본다. 쿼리 파라미터는 값이 있는 것만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service `GET /internal/user-service/admin/payment-orders` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/payment-orders")
    public ResponseEntity<byte[]> paymentOrders(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "사용자 ID로 거른다. 생략하면 전체.", example = "42") @RequestParam(name = "userId", required = false) Long userId,
            @Parameter(description = "상태 필터. 하위 API로 그대로 넘긴다(허용 값은 하위 API 문서).", example = "pending") @RequestParam(name = "status", required = false) String status,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        return facadeService.user(HttpMethod.GET, AdminQueryString.path("/internal/user-service/admin/payment-orders")
                .param("userId", userId)
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", limit)
                .build(), null);
    }

    // 초기화권 원장을 사용자·지급 출처로 걸러 최신순으로 조회한다
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "초기화권 원장", description = """
            user-service `GET /internal/user-service/admin/reset-passes`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 user-service 문서의 해당 API를 본다. 쿼리 파라미터는 값이 있는 것만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service `GET /internal/user-service/admin/reset-passes` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/reset-passes")
    public ResponseEntity<byte[]> resetPasses(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "사용자 ID로 거른다. 생략하면 전체.", example = "42") @RequestParam(name = "userId", required = false) Long userId,
            @Parameter(description = "지급 출처 필터. 하위 API로 그대로 넘긴다(허용 값은 하위 API 문서).", example = "purchase") @RequestParam(name = "sourceType", required = false) String sourceType,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        return facadeService.user(HttpMethod.GET, AdminQueryString.path("/internal/user-service/admin/reset-passes")
                .param("userId", userId)
                .param("sourceType", sourceType)
                .param("cursor", cursor)
                .param("limit", limit)
                .build(), null);
    }

    // 지갑 초기화 이력을 사용자로 걸러 시즌 이름과 함께 최신순으로 조회한다
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "자산 초기화 이력", description = """
            user-service 자산 초기화 이력 한 페이지에, 각 행의 시즌 이름을 trading-service 시즌 목록에서 찾아 붙인다(못 찾거나 시즌 조회가 실패하면 `null`). 읽기 전용이다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service 자산 초기화 이력 응답의 `success` 객체에서 각 `items[]` 행에 `seasonName`(시즌 이름, 모르면 `null`)을 더한 것.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_ERROR, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/reset-histories")
    public ResponseEntity<ApiResponse<ObjectNode>> resetHistories(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "사용자 ID로 거른다. 생략하면 전체.", example = "42") @RequestParam(name = "userId", required = false) Long userId,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        return ResponseEntity.ok(ApiResponse.success(facadeService.queries().resetHistories(userId, cursor, limit)));
    }

    // 누락된 사용자 지갑을 trading-service에 강제 생성한다
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "지갑 보장(없으면 생성)", description = """
            trading-service `POST /internal/trading-service/admin/users/{userId}/wallet/ensure`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 trading-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "trading-service `POST /internal/trading-service/admin/users/{userId}/wallet/ensure` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/users/{userId}/wallet/ensure")
    public ResponseEntity<byte[]> ensureWallet(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token, @Parameter(description = "사용자 ID", example = "42") @PathVariable Long userId, HttpServletRequest request) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.trading(HttpMethod.POST, "/internal/trading-service/admin/users/" + userId + "/wallet/ensure", null);
    }

    // 특정 사용자의 주문 내역을 trading-service admin endpoint에서 조회한다
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "사용자 주문 목록", description = """
            trading-service `GET /internal/trading-service/admin/users/{userId}/orders`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 trading-service 문서의 해당 API를 본다. 쿼리 파라미터는 값이 있는 것만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "trading-service `GET /internal/trading-service/admin/users/{userId}/orders` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/users/{userId}/orders")
    public ResponseEntity<byte[]> userOrders(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "사용자 ID", example = "42") @PathVariable Long userId,
            @Parameter(description = "주문 상태 필터. 하위 API로 그대로 넘긴다(허용 값은 하위 API 문서).", example = "wait") @RequestParam(name = "state", required = false) String state,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        String downstreamPath = UriComponentsBuilder.fromPath("/internal/trading-service/admin/users/{userId}/orders")
                .queryParamIfPresent("state", Optional.ofNullable(state))
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor))
                .queryParamIfPresent("limit", Optional.ofNullable(limit))
                .buildAndExpand(userId)
                .toUriString();
        return facadeService.trading(HttpMethod.GET, downstreamPath, null);
    }

    // 랭킹 시즌 목록을 trading-service admin endpoint에서 조회한다
    @Tag(name = "관리자 콘솔: 랭킹 시즌", description = "랭킹 시즌을 만들고 고치며, 시즌 순위표를 보고 다시 계산한다. trading-service 관리 API로 전달한다.")
    @Operation(summary = "시즌 목록", description = """
            trading-service `GET /internal/trading-service/admin/ranking/seasons`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 trading-service 문서의 해당 API를 본다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "trading-service `GET /internal/trading-service/admin/ranking/seasons` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/ranking/seasons")
    public ResponseEntity<byte[]> rankingSeasons(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token) {
        facadeService.verifyGatewayAdmin(token);
        return facadeService.trading(HttpMethod.GET, "/internal/trading-service/admin/ranking/seasons", null);
    }

    // 신규 랭킹 시즌을 trading-service admin endpoint에 생성한다
    @Tag(name = "관리자 콘솔: 랭킹 시즌")
    @Operation(summary = "시즌 생성", description = """
            trading-service `POST /internal/trading-service/admin/ranking/seasons`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 trading-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "trading-service `POST /internal/trading-service/admin/ranking/seasons` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/ranking/seasons")
    public ResponseEntity<byte[]> createRankingSeason(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token, @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "trading-service `SeasonCommand`와 같은 JSON. 그대로 하위 API로 넘긴다.", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", example = "{\"name\": \"2026 시즌 1\", \"startsAt\": \"2026-01-01T00:00:00Z\", \"endsAt\": \"2026-03-31T15:00:00Z\"}"))) @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.trading(HttpMethod.POST, "/internal/trading-service/admin/ranking/seasons", body);
    }

    // 기존 랭킹 시즌 메타데이터를 trading-service admin endpoint에서 수정한다
    @Tag(name = "관리자 콘솔: 랭킹 시즌")
    @Operation(summary = "시즌 수정", description = """
            trading-service `PUT /internal/trading-service/admin/ranking/seasons/{seasonId}`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 trading-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "trading-service `PUT /internal/trading-service/admin/ranking/seasons/{seasonId}` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PutMapping("/ranking/seasons/{seasonId}")
    public ResponseEntity<byte[]> updateRankingSeason(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token, @Parameter(description = "시즌 ID", example = "3") @PathVariable Long seasonId, @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "trading-service `SeasonCommand`와 같은 JSON. 그대로 하위 API로 넘긴다.", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", example = "{\"name\": \"2026 시즌 1\", \"startsAt\": \"2026-01-01T00:00:00Z\", \"endsAt\": \"2026-03-31T15:00:00Z\"}"))) @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.trading(HttpMethod.PUT, "/internal/trading-service/admin/ranking/seasons/" + seasonId, body);
    }

    // 시즌 랭킹 보드를 강제 재계산하도록 trading-service에 명령한다
    @Tag(name = "관리자 콘솔: 랭킹 시즌")
    @Operation(summary = "시즌 순위 재계산", description = """
            trading-service `POST /internal/trading-service/admin/ranking/seasons/{seasonId}/recalculate`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 trading-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "trading-service `POST /internal/trading-service/admin/ranking/seasons/{seasonId}/recalculate` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/ranking/seasons/{seasonId}/recalculate")
    public ResponseEntity<byte[]> recalculateRankingSeason(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token, @Parameter(description = "시즌 ID", example = "3") @PathVariable Long seasonId, HttpServletRequest request) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.trading(HttpMethod.POST, "/internal/trading-service/admin/ranking/seasons/" + seasonId + "/recalculate", null);
    }

    // 시즌 랭킹을 rank 순 페이지로 조회하고 닉네임을 붙인다
    @Tag(name = "관리자 콘솔: 랭킹 시즌")
    @Operation(summary = "시즌 순위표", description = """
            trading-service 시즌 랭킹 행 한 페이지에 user-service 닉네임을 붙인다(이미 `nickname`이 있는 행은 그대로, 못 찾으면 `null`). 읽기 전용이다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "trading-service 시즌 랭킹 행 목록 응답의 `success` 객체에서 각 `rankings[]` 행에 `nickname`을 채운 것.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_ERROR, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/ranking/seasons/{seasonId}/rankings")
    public ResponseEntity<ApiResponse<ObjectNode>> seasonRankings(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "시즌 ID", example = "3") @PathVariable Long seasonId,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        return ResponseEntity.ok(ApiResponse.success(facadeService.queries().seasonRankings(seasonId, cursor, limit)));
    }

    // CSRF·관리자 인증 후 IAP 수동 지급 요청을 user-service admin endpoint로 프록시한다.
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "IAP 수동 지급", description = """
            user-service `POST /internal/user-service/admin/iap/grant`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 user-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service `POST /internal/user-service/admin/iap/grant` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/iap/grant")
    public ResponseEntity<byte[]> grantIap(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token, @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "user-service `ManualIapGrantRequest`와 같은 JSON. 그대로 하위 API로 넘긴다.", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", example = "{\"userId\": 42, \"orderId\": \"toss-order-20260101-0001\", \"sku\": \"mockcoin.reset_pass.1\"}"))) @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.user(HttpMethod.POST, "/internal/user-service/admin/iap/grant", body);
    }

    // CSRF·관리자 인증 후 IAP 수동 환불 요청을 user-service admin endpoint로 프록시한다.
    @Tag(name = "관리자 콘솔: 사용자·결제")
    @Operation(summary = "IAP 수동 환불", description = """
            user-service `POST /internal/user-service/admin/iap/refund`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 user-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "user-service `POST /internal/user-service/admin/iap/refund` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/iap/refund")
    public ResponseEntity<byte[]> refundIap(@Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token, @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "user-service `ManualRefundRequest`와 같은 JSON. 그대로 하위 API로 넘긴다.", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", example = "{\"userId\": 42, \"purchaseId\": 15}"))) @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.user(HttpMethod.POST, "/internal/user-service/admin/iap/refund", body);
    }

    // 개별 신고 목록을 상태·cursor·limit 조건으로 community-service admin endpoint에서 조회한다.
    @Tag(name = "관리자 콘솔: 커뮤니티", description = "커뮤니티 신고 검수와 게시글·댓글 숨김·복구·삭제. community-service 관리 API로 그대로 전달한다.")
    @Operation(summary = "개별 신고 목록", description = """
            community-service `GET /internal/community-service/admin/reports`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 쿼리 파라미터는 값이 있는 것만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `GET /internal/community-service/admin/reports` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/community/reports")
    public ResponseEntity<byte[]> communityReports(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "상태 필터. 하위 API로 그대로 넘긴다(허용 값은 하위 API 문서).", example = "pending") @RequestParam(name = "status", required = false) String status,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.GET, AdminQueryString.path("/internal/community-service/admin/reports")
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", limit)
                .build(), null);
    }

    // 신고를 대상 단위로 묶은 검수 목록 (status 기본값 pending)
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "신고 대상 목록(검수 대기열)", description = """
            community-service `GET /internal/community-service/admin/report-targets`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 쿼리 파라미터는 값이 있는 것만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `GET /internal/community-service/admin/report-targets` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/community/report-targets")
    public ResponseEntity<byte[]> communityReportTargets(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "상태 필터. 하위 API로 그대로 넘긴다(허용 값은 하위 API 문서).", example = "pending") @RequestParam(name = "status", required = false) String status,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.GET, AdminQueryString.path("/internal/community-service/admin/report-targets")
                .param("status", status)
                .param("cursor", cursor)
                .param("limit", limit)
                .build(), null);
    }

    // 한 대상에 접수된 개별 신고 목록
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "대상별 신고 목록", description = """
            community-service `GET /internal/community-service/admin/report-targets/{targetType}/{targetId}/reports`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 쿼리 파라미터는 값이 있는 것만 넘긴다. 신고 대상 유형은 게이트웨이가 먼저 `post`·`comment`만 통과시킨다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `GET /internal/community-service/admin/report-targets/{targetType}/{targetId}/reports` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @CommonApiErrors(CommonErrorCode.INVALID_PARAMETER)
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/community/report-targets/{targetType}/{targetId}/reports")
    public ResponseEntity<byte[]> communityTargetReports(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "신고 대상 유형. `post`=게시글, `comment`=댓글. 소문자만 받으며 다른 값은 400이다.", schema = @Schema(allowableValues = {"post", "comment"}, example = "post")) @PathVariable String targetType,
            @Parameter(description = "신고 대상 게시글 또는 댓글 ID", example = "101") @PathVariable Long targetId,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.GET, AdminQueryString.path(
                        "/internal/community-service/admin/report-targets/" + requireReportTargetType(targetType) + "/" + targetId + "/reports")
                .param("cursor", cursor)
                .param("limit", limit)
                .build(), null);
    }

    // 대상의 대기 신고를 인정/기각하고 콘텐츠 조치를 함께 적용한다
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "신고 처리", description = """
            community-service `POST /internal/community-service/admin/report-targets/{targetType}/{targetId}/process`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 신고 대상 유형은 게이트웨이가 먼저 `post`·`comment`만 통과시킨다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `POST /internal/community-service/admin/report-targets/{targetType}/{targetId}/process` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @CommonApiErrors(CommonErrorCode.INVALID_PARAMETER)
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/community/report-targets/{targetType}/{targetId}/process")
    public ResponseEntity<byte[]> processCommunityReportTarget(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "신고 대상 유형. `post`=게시글, `comment`=댓글. 소문자만 받으며 다른 값은 400이다.", schema = @Schema(allowableValues = {"post", "comment"}, example = "post")) @PathVariable String targetType,
            @Parameter(description = "신고 대상 게시글 또는 댓글 ID", example = "101") @PathVariable Long targetId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "community-service `ProcessReportRequest`와 같은 JSON(`decision`, `contentAction`). 그대로 하위 API로 넘긴다.", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", example = "{\"decision\": \"resolve\", \"contentAction\": \"hide\"}"))) @RequestBody(required = false) byte[] body,
            HttpServletRequest request
    ) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.POST,
                "/internal/community-service/admin/report-targets/" + requireReportTargetType(targetType) + "/" + targetId + "/process",
                body);
    }

    // 공개 게시글 목록을 cursor 페이지로 community-service admin endpoint에서 조회한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "공개 게시글 목록", description = """
            community-service `GET /internal/community-service/admin/published-posts`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 쿼리 파라미터는 값이 있는 것만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `GET /internal/community-service/admin/published-posts` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/community/published-posts")
    public ResponseEntity<byte[]> communityPublishedPosts(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        String downstreamPath = UriComponentsBuilder.fromPath("/internal/community-service/admin/published-posts")
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor))
                .queryParamIfPresent("limit", Optional.ofNullable(limit))
                .toUriString();
        return facadeService.community(HttpMethod.GET, downstreamPath, null);
    }

    // 숨김 게시글 목록을 cursor 페이지로 community-service admin endpoint에서 조회한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "숨김 게시글 목록", description = """
            community-service `GET /internal/community-service/admin/hidden-posts`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 쿼리 파라미터는 값이 있는 것만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `GET /internal/community-service/admin/hidden-posts` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/community/hidden-posts")
    public ResponseEntity<byte[]> communityHiddenPosts(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        String downstreamPath = UriComponentsBuilder.fromPath("/internal/community-service/admin/hidden-posts")
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor))
                .queryParamIfPresent("limit", Optional.ofNullable(limit))
                .toUriString();
        return facadeService.community(HttpMethod.GET, downstreamPath, null);
    }

    // 공개 댓글 목록을 cursor 페이지로 community-service admin endpoint에서 조회한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "공개 댓글 목록", description = """
            community-service `GET /internal/community-service/admin/published-comments`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 쿼리 파라미터는 값이 있는 것만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `GET /internal/community-service/admin/published-comments` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/community/published-comments")
    public ResponseEntity<byte[]> communityPublishedComments(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        String downstreamPath = UriComponentsBuilder.fromPath("/internal/community-service/admin/published-comments")
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor))
                .queryParamIfPresent("limit", Optional.ofNullable(limit))
                .toUriString();
        return facadeService.community(HttpMethod.GET, downstreamPath, null);
    }

    // 숨김 댓글 목록을 cursor 페이지로 community-service admin endpoint에서 조회한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "숨김 댓글 목록", description = """
            community-service `GET /internal/community-service/admin/hidden-comments`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 쿼리 파라미터는 값이 있는 것만 넘긴다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `GET /internal/community-service/admin/hidden-comments` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/community/hidden-comments")
    public ResponseEntity<byte[]> communityHiddenComments(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "이전 응답의 `nextCursor`. 하위 API로 그대로 넘긴다.", example = "0") @RequestParam(name = "cursor", required = false) String cursor,
            @Parameter(description = "한 페이지 크기. 하위 API로 그대로 넘긴다(허용 범위는 하위 API 문서).", example = "20") @RequestParam(name = "limit", required = false) Integer limit
    ) {
        facadeService.verifyGatewayAdmin(token);
        String downstreamPath = UriComponentsBuilder.fromPath("/internal/community-service/admin/hidden-comments")
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor))
                .queryParamIfPresent("limit", Optional.ofNullable(limit))
                .toUriString();
        return facadeService.community(HttpMethod.GET, downstreamPath, null);
    }

    // 게시글 상세를 community-service admin endpoint에서 조회한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "게시글 상세", description = """
            community-service `GET /internal/community-service/admin/posts/{postId}`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `GET /internal/community-service/admin/posts/{postId}` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/community/posts/{postId}")
    public ResponseEntity<byte[]> communityPost(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "게시글 ID", example = "101") @PathVariable Long postId
    ) {
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.GET, "/internal/community-service/admin/posts/" + postId, null);
    }

    // 댓글 상세를 community-service admin endpoint에서 조회한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "댓글 상세", description = """
            community-service `GET /internal/community-service/admin/comments/{commentId}`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `GET /internal/community-service/admin/comments/{commentId}` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @GetMapping("/community/comments/{commentId}")
    public ResponseEntity<byte[]> communityComment(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "댓글 ID", example = "555") @PathVariable Long commentId
    ) {
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.GET, "/internal/community-service/admin/comments/" + commentId, null);
    }

    // CSRF·관리자 인증 후 게시글 숨김을 community-service admin endpoint에 요청한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "게시글 숨김", description = """
            community-service `POST /internal/community-service/admin/posts/{postId}/hide`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `POST /internal/community-service/admin/posts/{postId}/hide` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/community/posts/{postId}/hide")
    public ResponseEntity<byte[]> hideCommunityPost(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "게시글 ID", example = "101") @PathVariable Long postId,
            HttpServletRequest request
    ) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.POST, "/internal/community-service/admin/posts/" + postId + "/hide", null);
    }

    // CSRF·관리자 인증 후 게시글 숨김 해제를 community-service admin endpoint에 요청한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "게시글 숨김 해제", description = """
            community-service `POST /internal/community-service/admin/posts/{postId}/unhide`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `POST /internal/community-service/admin/posts/{postId}/unhide` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/community/posts/{postId}/unhide")
    public ResponseEntity<byte[]> unhideCommunityPost(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "게시글 ID", example = "101") @PathVariable Long postId,
            HttpServletRequest request
    ) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.POST, "/internal/community-service/admin/posts/" + postId + "/unhide", null);
    }

    // CSRF·관리자 인증 후 게시글 삭제를 community-service admin endpoint에 요청한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "게시글 삭제", description = """
            community-service `POST /internal/community-service/admin/posts/{postId}/delete`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `POST /internal/community-service/admin/posts/{postId}/delete` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/community/posts/{postId}/delete")
    public ResponseEntity<byte[]> deleteCommunityPost(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "게시글 ID", example = "101") @PathVariable Long postId,
            HttpServletRequest request
    ) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.POST, "/internal/community-service/admin/posts/" + postId + "/delete", null);
    }

    // CSRF·관리자 인증 후 댓글 숨김을 community-service admin endpoint에 요청한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "댓글 숨김", description = """
            community-service `POST /internal/community-service/admin/comments/{commentId}/hide`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `POST /internal/community-service/admin/comments/{commentId}/hide` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/community/comments/{commentId}/hide")
    public ResponseEntity<byte[]> hideCommunityComment(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "댓글 ID", example = "555") @PathVariable Long commentId,
            HttpServletRequest request
    ) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.POST, "/internal/community-service/admin/comments/" + commentId + "/hide", null);
    }

    // CSRF·관리자 인증 후 댓글 숨김 해제를 community-service admin endpoint에 요청한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "댓글 숨김 해제", description = """
            community-service `POST /internal/community-service/admin/comments/{commentId}/unhide`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `POST /internal/community-service/admin/comments/{commentId}/unhide` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/community/comments/{commentId}/unhide")
    public ResponseEntity<byte[]> unhideCommunityComment(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "댓글 ID", example = "555") @PathVariable Long commentId,
            HttpServletRequest request
    ) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.POST, "/internal/community-service/admin/comments/" + commentId + "/unhide", null);
    }

    // CSRF·관리자 인증 후 댓글 삭제를 community-service admin endpoint에 요청한다.
    @Tag(name = "관리자 콘솔: 커뮤니티")
    @Operation(summary = "댓글 삭제", description = """
            community-service `POST /internal/community-service/admin/comments/{commentId}/delete`로 그대로 전달하고 하위 응답(상태 코드·본문)을 그대로 돌려준다. 요청·응답 형식, 동작, 업무 에러는 community-service 문서의 해당 API를 본다. 세션 쿠키로 부르면 같은 출처(Origin·Referer) 검사를 먼저 한다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(type = "object", description = "community-service `POST /internal/community-service/admin/comments/{commentId}/delete` 응답의 `success`를 그대로 담는다. 하위 API가 실패로 답하면 이 성공 형식 대신 하위 실패 응답(상태 코드·본문)을 그대로 준다.")),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ADMIN_ORIGIN_REJECTED, GatewayErrorCode.DOWNSTREAM_ADMIN_TOKEN_NOT_CONFIGURED, GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE})
    @PostMapping("/community/comments/{commentId}/delete")
    public ResponseEntity<byte[]> deleteCommunityComment(
            @Parameter(hidden = true) @RequestHeader(name = GatewayAdminProperties.ADMIN_TOKEN_HEADER, required = false) String token,
            @Parameter(description = "댓글 ID", example = "555") @PathVariable Long commentId,
            HttpServletRequest request
    ) {
        csrfGuard.verifyUnsafeCookieRequest(request, token);
        facadeService.verifyGatewayAdmin(token);
        return facadeService.community(HttpMethod.POST, "/internal/community-service/admin/comments/" + commentId + "/delete", null);
    }

    // downstream 경로에 그대로 붙이므로 허용된 대상 유형만 통과시킨다 (경로 조작 방지)
    private static String requireReportTargetType(String targetType) {
        if (targetType == null || !REPORT_TARGET_TYPES.contains(targetType)) {
            throw CommonErrorCode.INVALID_PARAMETER.exception("신고 대상 유형이 올바르지 않습니다.");
        }
        return targetType;
    }
}
