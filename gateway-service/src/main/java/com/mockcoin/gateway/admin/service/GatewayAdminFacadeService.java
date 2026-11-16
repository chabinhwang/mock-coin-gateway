package com.mockcoin.gateway.admin.service;

import tools.jackson.databind.node.ObjectNode;
import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.integration.AdminDownstreamClient;
import com.mockcoin.gateway.global.error.GatewayErrorCode;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

// admin 콘솔 facade: 접근 인증 + downstream 프록시/overview 집계 위임
@Service
public class GatewayAdminFacadeService {
    private final GatewayAdminProperties adminProperties;
    private final AdminDownstreamClient downstreamClient;
    private final AdminOverviewAggregator overviewAggregator;
    private final AdminConsoleQueryAggregator queryAggregator;

    // 관리자 설정, downstream 클라이언트, overview 집계기, 조회 집계기를 주입받는다.
    public GatewayAdminFacadeService(
            GatewayAdminProperties adminProperties,
            AdminDownstreamClient downstreamClient,
            AdminOverviewAggregator overviewAggregator,
            AdminConsoleQueryAggregator queryAggregator
    ) {
        this.adminProperties = adminProperties;
        this.downstreamClient = downstreamClient;
        this.overviewAggregator = overviewAggregator;
        this.queryAggregator = queryAggregator;
    }

    // 헤더 토큰 또는 admin 세션 권한 중 하나로 admin facade 접근을 인증한다
    public void verifyGatewayAdmin(String token) {
        if (isGatewayAdminTokenValid(token)) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN_CONSOLE".equals(authority.getAuthority()))) {
            return;
        }
        if (adminProperties.internalToken().isBlank()) {
            throw GatewayErrorCode.ADMIN_TOKEN_NOT_CONFIGURED.exception();
        }
        throw GatewayErrorCode.ADMIN_AUTH_REQUIRED.exception();
    }

    // 외부 호출 admin 토큰이 설정값과 일치하는지 확인한다
    public boolean isGatewayAdminTokenValid(String token) {
        String expected = adminProperties.internalToken();
        return !expected.isBlank() && expected.equals(token);
    }

    // user-service internal admin endpoint로 요청을 프록시한다
    public ResponseEntity<byte[]> user(HttpMethod method, String path, byte[] body) {
        return downstreamClient.user(method, path, body);
    }

    // trading-service internal admin endpoint로 요청을 프록시한다
    public ResponseEntity<byte[]> trading(HttpMethod method, String path, byte[] body) {
        return downstreamClient.trading(method, path, body);
    }

    // community-service internal admin endpoint로 요청을 프록시한다.
    public ResponseEntity<byte[]> community(HttpMethod method, String path, byte[] body) {
        return downstreamClient.community(method, path, body);
    }

    // user/trading/market/ingestor overview fragment를 병렬 조회해 합쳐 반환한다
    public ObjectNode overview() {
        return overviewAggregator.overview();
    }

    // 여러 서비스 데이터를 페이지 행 기준으로 합치는 탭 조회 집계기
    public AdminConsoleQueryAggregator queries() {
        return queryAggregator;
    }
}
