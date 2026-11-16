package com.mockcoin.gateway.global.controller;

import java.util.List;

import com.mockcoin.gateway.global.service.GatewayTopologyService;
import com.mockcoin.shared.api.ApiResponse;
import com.mockcoin.shared.service.ServiceDescriptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// MSA 토폴로지(서비스/포트/소유 테이블) 조회용 내부 컨트롤러
@RestController
@RequestMapping("/internal/topology")
@Tag(name = "내부: 서비스 정보", description = """
        전체 서비스 목록(이름·기본 포트·소유 테이블)을 알려 주는 운영 확인용 API다. 이 저장소의 서비스 코드 중 이 API를 부르는 곳은 없다.
        내부 토큰 검사 대상이 아니다.""")
public class GatewayTopologyController {
    private final GatewayTopologyService topologyService;

    // 토폴로지 서비스를 주입받는다.
    public GatewayTopologyController(GatewayTopologyService topologyService) {
        this.topologyService = topologyService;
    }

    // 등록된 서비스 디스크립터 목록을 반환한다
    @GetMapping
    @Operation(summary = "서비스 토폴로지", description = "gateway가 코드에 고정해 둔 6개 서비스의 이름·기본 포트·소유 테이블 목록을 준다(하위 서비스를 부르지 않는다). 읽기 전용이다.")
    public ApiResponse<List<ServiceDescriptor>> topology() {
        return ApiResponse.ok(topologyService.descriptors());
    }
}
