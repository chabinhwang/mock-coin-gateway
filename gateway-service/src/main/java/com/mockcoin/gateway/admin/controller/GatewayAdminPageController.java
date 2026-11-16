package com.mockcoin.gateway.admin.controller;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// admin 콘솔 루트 경로 진입 시 정적 index.html로 리다이렉트한다
@Controller
@ConditionalOnProperty(prefix = "mockcoin.admin", name = "enabled", havingValue = "true")
@Tag(name = "관리자 콘솔 화면", description = "관리자 콘솔 웹 화면(정적 파일 `index.html`·`login.html` 등)의 진입 경로. 관리자 기능이 켜진 환경에만 있다.")
public class GatewayAdminPageController {
    private final GatewayAdminProperties properties;

    // 관리자 설정을 주입받는다.
    public GatewayAdminPageController(GatewayAdminProperties properties) {
        this.properties = properties;
    }

    // /admin 또는 /admin/ 요청을 /admin/index.html로 보낸다
    @GetMapping({"${mockcoin.admin.base-path:/admin}", "${mockcoin.admin.base-path:/admin}/"})
    @Operation(summary = "관리자 콘솔 열기", description = "관리자 콘솔 첫 화면(`index.html`)으로 보낸다. 로그인하지 않았으면 `index.html` 요청은 관리자 보안 설정에 따라 `login.html`로 다시 이동한다.")
    @ApiResponse(responseCode = "302", description = "`<관리자 경로>/index.html`로 이동",
            headers = @Header(name = "Location", description = "이동할 주소", schema = @Schema(type = "string", example = "/admin/index.html")))
    public String adminPage() {
        return "redirect:" + properties.basePath() + "/index.html";
    }
}
