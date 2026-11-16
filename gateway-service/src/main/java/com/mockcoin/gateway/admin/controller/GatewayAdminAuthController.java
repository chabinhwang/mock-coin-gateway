package com.mockcoin.gateway.admin.controller;

import java.util.Map;
import java.util.Optional;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.service.GatewayAdminAuthService;
import com.mockcoin.gateway.admin.service.GatewayAdminSessionAuthFilter;
import com.mockcoin.gateway.admin.service.GatewayAdminWebAuthnService;
import com.mockcoin.gateway.global.error.GatewayApiErrors;
import com.mockcoin.gateway.global.error.GatewayErrorCode;
import com.mockcoin.gateway.global.security.GatewaySecurityHardeningProperties;
import com.mockcoin.gateway.global.security.TrustedProxyResolver;
import com.mockcoin.shared.api.ApiResponse;
import com.mockcoin.shared.api.ErrorCode;
import com.mockcoin.shared.api.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

// admin 콘솔 로그인/로그아웃 및 WebAuthn 패스키 등록·로그인 API
@RestController
@ConditionalOnProperty(prefix = "mockcoin.admin", name = "enabled", havingValue = "true")
@RequestMapping("${mockcoin.admin.base-path:/admin}/api/auth")
@Tag(name = "관리자 로그인", description = """
        관리자 콘솔(브라우저)의 로그인·로그아웃과 패스키(WebAuthn) 등록·로그인. 관리자 기능이 켜진 환경(`mockcoin.admin.enabled=true`)에만 있다.
        로그인에 성공하면 `ADMIN_SESSION` 쿠키(HttpOnly, SameSite=Strict, 30분)를 준다. 같은 IP에서 로그인 실패가 5번 쌓이면 30분 동안 잠긴다.
        패스키를 켠 환경에서는 비밀번호 로그인이 '패스키 등록 전용 세션'만 주고, 패스키를 등록하면 관리자 세션으로 바뀐다.""")
public class GatewayAdminAuthController {
    private static final String SESSION_COOKIE_HEADER_DESCRIPTION =
            "`ADMIN_SESSION=<세션 ID>; Path=<관리자 경로>; Max-Age=1800; HttpOnly; SameSite=Strict`(운영 설정에 따라 `Secure`).";

    private final GatewayAdminAuthService authService;
    private final GatewayAdminWebAuthnService webAuthnService;
    private final GatewayAdminProperties properties;
    private final GatewaySecurityHardeningProperties securityProperties;

    // 관리자 인증 서비스, WebAuthn 서비스, 관리자 설정, 보안 설정을 주입받는다.
    public GatewayAdminAuthController(
            GatewayAdminAuthService authService,
            GatewayAdminWebAuthnService webAuthnService,
            GatewayAdminProperties properties,
            GatewaySecurityHardeningProperties securityProperties) {
        this.authService = authService;
        this.webAuthnService = webAuthnService;
        this.properties = properties;
        this.securityProperties = securityProperties;
    }

    // 현재 admin 세션 상태(로그인/잠금/패스키 등록 여부)를 조회한다
    @GetMapping("/status")
    @Operation(summary = "로그인 상태", description = "로그인 화면이 어떤 로그인 방식을 보여 줄지 정할 때 부른다. 쿠키(`ADMIN_SESSION`)가 있으면 그 세션을 확인한다. 읽기 전용이다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(implementation = AdminAuthStatus.class)),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(HttpServletRequest request) {
        String sessionId = GatewayAdminSessionAuthFilter.extractSessionCookie(request);
        return ResponseEntity.ok(ApiResponse.success(authService.getAuthStatus(resolveClientIp(request), sessionId)));
    }

    // id/password 기반 admin 로그인을 처리한다 (패스키 모드에서는 미등록 시에만 사용)
    @PostMapping("/login")
    @Operation(summary = "비밀번호 로그인", description = """
            관리자 아이디·비밀번호를 확인하고 세션 쿠키를 준다. 실패하면 IP별 실패 횟수를 늘린다(5번이면 30분 잠금).

            - 패스키가 꺼져 있으면: 관리자 세션(`adminSession=true`).
            - 패스키가 켜져 있고 아직 등록된 패스키가 없으면: 패스키 등록 전용 세션(`registrationRequired=true`). 이어서 패스키를 등록해야 관리자 기능을 쓸 수 있다.
            - 패스키가 켜져 있고 이미 등록돼 있으면: 403 `PASSKEY_REQUIRED`(로컬 프로필의 개발용 우회 설정을 켜면 관리자 세션).""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = SESSION_COOKIE_HEADER_DESCRIPTION, schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(implementation = AdminLoginResult.class)),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.ACCOUNT_LOCKED, GatewayErrorCode.PASSKEY_REQUIRED, GatewayErrorCode.INVALID_CREDENTIALS})
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        String ip = resolveClientIp(request);
        if (authService.isLocked(ip)) {
            return fail(GatewayErrorCode.ACCOUNT_LOCKED);
        }
        Optional<String> sessionId = authService.login(body.username(), body.password(), ip);
        if (sessionId.isEmpty()) {
            if (properties.passkeyEnabled() && webAuthnService.hasRegisteredPasskey()) {
                return fail(GatewayErrorCode.PASSKEY_REQUIRED);
            }
            return fail(GatewayErrorCode.INVALID_CREDENTIALS);
        }
        boolean adminSession = authService.validateSession(sessionId.get());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, createSessionCookie(sessionId.get()).toString())
                .body(ApiResponse.success(Map.of(
                        "message", "로그인 성공",
                        "adminSession", adminSession,
                        "registrationRequired", !adminSession)));
    }

    // admin 세션을 만료시키고 세션 쿠키를 제거한다
    @PostMapping("/logout")
    @Operation(summary = "로그아웃", description = "쿠키의 세션을 폐기하고 쿠키를 지운다(`Max-Age=0`). 세션이 없어도 성공으로 답한다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = "`ADMIN_SESSION=; Max-Age=0` — 세션 쿠키 삭제", schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(implementation = AdminMessage.class)),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    public ResponseEntity<ApiResponse<Map<String, String>>> logout(HttpServletRequest request) {
        authService.logout(GatewayAdminSessionAuthFilter.extractSessionCookie(request));
        ResponseCookie expired = ResponseCookie.from(GatewayAdminProperties.SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path(properties.basePath())
                .maxAge(0)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(ApiResponse.success(Map.of("message", "로그아웃 성공")));
    }

    // WebAuthn 패스키 등록 challenge/옵션을 발급한다
    @PostMapping("/webauthn/register/options")
    @Operation(summary = "패스키 등록 시작", description = """
            브라우저 `navigator.credentials.create()`에 넘길 등록 옵션과 challenge ID(5분 유효)를 준다.
            패스키 등록 전용 세션이나 관리자 세션이 필요하고, 이미 패스키가 있으면 관리자 세션이 필요하다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(implementation = WebAuthnOptions.class)),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.PASSKEY_DISABLED, GatewayErrorCode.ADMIN_SESSION_REQUIRED, GatewayErrorCode.REGISTRATION_SESSION_REQUIRED})
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerOptions(HttpServletRequest request) {
        if (!properties.passkeyEnabled()) {
            return passkeyDisabled();
        }
        String sessionId = GatewayAdminSessionAuthFilter.extractSessionCookie(request);
        if (webAuthnService.hasRegisteredPasskey() && !authService.validateSession(sessionId)) {
            return fail(GatewayErrorCode.ADMIN_SESSION_REQUIRED);
        }
        if (!authService.validateRegistrationSession(sessionId)) {
            return fail(GatewayErrorCode.REGISTRATION_SESSION_REQUIRED);
        }
        return ResponseEntity.ok(ApiResponse.success(webAuthnService.generateRegistrationOptions()));
    }

    // WebAuthn 패스키 등록을 마무리하고 등록 세션을 admin 세션으로 승격한다
    @PostMapping("/webauthn/register/finish")
    @Operation(summary = "패스키 등록 완료", description = """
            브라우저가 만든 등록 응답을 검증해 패스키를 저장하고, 지금 세션을 관리자 세션으로 바꾼다(다른 등록 전용 세션은 폐기).
            관리자 세션으로 부르면 기존 패스키를 바꾸고, 등록 전용 세션이면 패스키가 없을 때만 저장한다. challenge는 한 번만 쓸 수 있다.""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(implementation = AdminMessage.class)),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({
            GatewayErrorCode.PASSKEY_DISABLED,
            GatewayErrorCode.ADMIN_SESSION_REQUIRED,
            GatewayErrorCode.REGISTRATION_SESSION_REQUIRED,
            GatewayErrorCode.WEBAUTHN_REGISTRATION_FAILED
    })
    public ResponseEntity<ApiResponse<Map<String, String>>> registerFinish(@RequestBody WebAuthnFinishRequest body, HttpServletRequest request) {
        if (!properties.passkeyEnabled()) {
            return passkeyDisabled();
        }
        String sessionId = GatewayAdminSessionAuthFilter.extractSessionCookie(request);
        boolean adminSession = authService.validateSession(sessionId);
        if (webAuthnService.hasRegisteredPasskey() && !adminSession) {
            return fail(GatewayErrorCode.ADMIN_SESSION_REQUIRED);
        }
        if (!authService.validateRegistrationSession(sessionId)) {
            return fail(GatewayErrorCode.REGISTRATION_SESSION_REQUIRED);
        }
        if (!webAuthnService.finishRegistration(body.response(), body.challengeId(), adminSession)) {
            return fail(GatewayErrorCode.WEBAUTHN_REGISTRATION_FAILED);
        }
        authService.promoteRegistrationSession(sessionId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "패스키 등록 성공")));
    }

    // WebAuthn 패스키 로그인 challenge/옵션을 발급한다
    @PostMapping("/webauthn/login/options")
    @Operation(summary = "패스키 로그인 시작", description = "브라우저 `navigator.credentials.get()`에 넘길 로그인 옵션과 challenge ID(5분 유효)를 준다. 로그인 없이 부른다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(implementation = WebAuthnOptions.class)),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.PASSKEY_DISABLED, GatewayErrorCode.NO_PASSKEY})
    public ResponseEntity<ApiResponse<Map<String, Object>>> loginOptions() {
        if (!properties.passkeyEnabled()) {
            return passkeyDisabled();
        }
        if (!webAuthnService.hasRegisteredPasskey()) {
            return fail(GatewayErrorCode.NO_PASSKEY);
        }
        return ResponseEntity.ok(ApiResponse.success(webAuthnService.generateLoginOptions()));
    }

    // WebAuthn 패스키 로그인 응답을 검증하고 admin 세션을 발급한다
    @PostMapping("/webauthn/login/finish")
    @Operation(summary = "패스키 로그인 완료", description = """
            브라우저가 만든 로그인 응답을 검증하고 관리자 세션 쿠키를 준다. challenge는 한 번만 쓸 수 있다.
            IP가 잠겨 있으면 423이다(패스키 실패는 잠금 횟수에 넣지 않는다).""")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공. 결과는 본문 `success`에 담긴다.",
            headers = @Header(name = HttpHeaders.SET_COOKIE, description = SESSION_COOKIE_HEADER_DESCRIPTION, schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json", schemaProperties = {
                    @SchemaProperty(name = "resultType", schema = @Schema(description = "성공 응답은 항상 `SUCCESS`다.", allowableValues = "SUCCESS", example = "SUCCESS")),
                    @SchemaProperty(name = "success", schema = @Schema(implementation = AdminMessage.class)),
                    @SchemaProperty(name = "error", schema = @Schema(description = "성공 응답에서는 항상 `null`이다.", implementation = ErrorResponse.class))
            }))
    @GatewayApiErrors({GatewayErrorCode.PASSKEY_DISABLED, GatewayErrorCode.ACCOUNT_LOCKED, GatewayErrorCode.WEBAUTHN_LOGIN_FAILED})
    public ResponseEntity<ApiResponse<Map<String, String>>> loginFinish(@RequestBody WebAuthnFinishRequest body, HttpServletRequest request) {
        if (!properties.passkeyEnabled()) {
            return passkeyDisabled();
        }
        String ip = resolveClientIp(request);
        if (authService.isLocked(ip)) {
            return fail(GatewayErrorCode.ACCOUNT_LOCKED);
        }
        if (!webAuthnService.finishLogin(body.response(), body.challengeId())) {
            return fail(GatewayErrorCode.WEBAUTHN_LOGIN_FAILED);
        }
        String sessionId = authService.createAdminSession();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, createSessionCookie(sessionId).toString())
                .body(ApiResponse.success(Map.of("message", "패스키 로그인 성공")));
    }

    private <T> ResponseEntity<ApiResponse<T>> passkeyDisabled() {
        return fail(GatewayErrorCode.PASSKEY_DISABLED);
    }

    // 에러 코드의 HTTP 상태와 실패 봉투로 응답을 만든다.
    private static <T> ResponseEntity<ApiResponse<T>> fail(ErrorCode error) {
        return ResponseEntity.status(error.status()).body(ApiResponse.fail(error));
    }

    // admin 경로에 한정된 HttpOnly·SameSite=Strict 30분 세션 쿠키를 만든다(Secure는 설정을 따른다).
    private ResponseCookie createSessionCookie(String sessionId) {
        return ResponseCookie.from(GatewayAdminProperties.SESSION_COOKIE_NAME, sessionId)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path(properties.basePath())
                .maxAge(GatewayAdminProperties.SESSION_TIMEOUT_SECONDS)
                .build();
    }

    // 신뢰 프록시 설정을 반영해 실제 클라이언트 IP를 구한다.
    private String resolveClientIp(HttpServletRequest request) {
        return TrustedProxyResolver.resolveClientIp(
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                securityProperties.getTrustedProxyCidrs());
    }

    @Schema(description = "비밀번호 로그인 요청")
    public record LoginRequest(
            @Schema(description = "관리자 아이디(`GATEWAY_ADMIN_USERNAME`)", example = "admin")
            String username,
            @Schema(description = "관리자 비밀번호(`GATEWAY_ADMIN_PASSWORD`)", example = "change-me")
            String password
    ) {
    }

    @Schema(description = "패스키 등록·로그인 완료 요청")
    public record WebAuthnFinishRequest(
            @Schema(description = "시작 API가 준 `challengeId`", example = "3f2a1c9e-5b7d-4e8f-9a10-2b3c4d5e6f70")
            String challengeId,
            @Schema(description = "브라우저 WebAuthn API가 돌려준 PublicKeyCredential을 JSON 문자열로 직렬화한 값",
                    example = "{\"id\":\"AbC...\",\"type\":\"public-key\",\"response\":{}}")
            String response
    ) {
    }

    // 아래 record는 Map으로 만드는 응답의 모양을 문서에 보이기 위한 스키마다(응답 직렬화에는 쓰지 않는다)
    @Schema(description = "관리자 로그인 상태")
    record AdminAuthStatus(
            @Schema(description = "패스키 사용이 켜져 있으면 `true`", example = "true")
            boolean passkeyEnabled,
            @Schema(description = "등록된 패스키가 있으면 `true`", example = "true")
            boolean hasPasskey,
            @Schema(description = "이 IP가 로그인 잠금 중이면 `true`", example = "false")
            boolean isLocked,
            @Schema(description = "쿠키 세션이 관리자 세션이면 `true`", example = "false")
            boolean authenticated,
            @Schema(description = "패스키 등록 전용 세션이라 패스키 등록이 필요하면 `true`", example = "false")
            boolean registrationRequired,
            @Schema(description = "로컬 프로필에서 패스키 대신 비밀번호로 관리자 세션을 주는 개발용 우회가 켜져 있으면 `true`", example = "false")
            boolean devPasswordBypassEnabled
    ) {
    }

    @Schema(description = "비밀번호 로그인 결과")
    record AdminLoginResult(
            @Schema(description = "항상 '로그인 성공'", example = "로그인 성공")
            String message,
            @Schema(description = "관리자 세션을 받았으면 `true`", example = "false")
            boolean adminSession,
            @Schema(description = "패스키 등록 전용 세션을 받아 패스키 등록이 필요하면 `true`(`adminSession`의 반대)", example = "true")
            boolean registrationRequired
    ) {
    }

    @Schema(description = "처리 결과 문구")
    record AdminMessage(
            @Schema(description = "결과 문구. '로그아웃 성공'·'패스키 등록 성공'·'패스키 로그인 성공' 중 하나.", example = "패스키 로그인 성공")
            String message
    ) {
    }

    @Schema(description = "WebAuthn 시작 결과")
    record WebAuthnOptions(
            @Schema(description = "완료 API에 다시 보낼 challenge ID(5분 유효, 한 번만 사용)", example = "3f2a1c9e-5b7d-4e8f-9a10-2b3c4d5e6f70")
            String challengeId,
            @Schema(description = "브라우저 WebAuthn API에 넘길 옵션(JSON 객체, WebAuthn 표준 `publicKey` 옵션)", type = "object",
                    example = "{\"publicKey\": {\"challenge\": \"q1w2e3...\", \"rpId\": \"localhost\", \"userVerification\": \"preferred\"}}")
            Object options
    ) {
    }
}
