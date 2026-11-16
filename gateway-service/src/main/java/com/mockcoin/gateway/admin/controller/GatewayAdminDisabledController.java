package com.mockcoin.gateway.admin.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// admin 비활성 상태에서는 /admin/** 요청을 정적 리소스 fallback 대신 명시적으로 숨긴다
@RestController
@ConditionalOnProperty(prefix = "mockcoin.admin", name = "enabled", havingValue = "false", matchIfMissing = true)
public class GatewayAdminDisabledController {
    // admin이 꺼져 있을 때 admin 경로의 모든 요청에 404를 반환한다.
    @RequestMapping({
            "${mockcoin.admin.base-path:/admin}",
            "${mockcoin.admin.base-path:/admin}/",
            "${mockcoin.admin.base-path:/admin}/**"
    })
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }
}
