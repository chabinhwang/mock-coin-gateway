package com.mockcoin.gateway;

import static com.mockcoin.shared.apidocs.testing.ApiDocsVerification.notReturned;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import com.mockcoin.gateway.admin.controller.GatewayAdminFacadeController;
import com.mockcoin.gateway.global.error.GatewayErrorCode;
import com.mockcoin.shared.apidocs.testing.ApiDocsVerification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 코드(컨트롤러·DTO·에러 정의)에서 만든 API 명세를 검증한다. 명세 갱신: cd server && ./scripts/generate-api-docs.sh
// 관리자 API까지 문서에 넣기 위해 관리자 기능을 켠 설정으로 띄운다.
@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=true",
        "mockcoin.admin.enabled=true",
        "mockcoin.admin.username=admin",
        "mockcoin.admin.password=secret",
        "mockcoin.admin.webauthn-rp-id=localhost",
        "mockcoin.admin.webauthn-origins=http://localhost"
})
@AutoConfigureMockMvc
class GatewayApiDocsTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private ApiDocsVerification verification;

    @BeforeEach
    void setUp() {
        verification = ApiDocsVerification.of("gateway-service", mockMvc, handlerMapping);
    }

    @Test
    @Tag(ApiDocsVerification.SNAPSHOT_TAG)
    void 명세가_커밋된_파일과_같다() {
        verification.assertMatchesCommittedSpec();
    }

    @Test
    void 모든_API와_필드에_설명이_있다() {
        verification.assertDocumented();
    }

    @Test
    void 선언한_에러가_코드가_던지는_에러와_맞다() {
        String overviewSwallowed = "대시보드 현황은 닉네임·지갑 보조 조회 실패를 삼키고 빈 값과 degradedServices로 알린다(AdminOverviewAggregator).";
        verification.assertErrorDeclarationsMatchCode(
                notReturned(GatewayAdminFacadeController.class, "overview", GatewayErrorCode.DOWNSTREAM_ADMIN_FAILED, overviewSwallowed),
                notReturned(GatewayAdminFacadeController.class, "overview", GatewayErrorCode.DOWNSTREAM_ADMIN_ERROR, overviewSwallowed),
                notReturned(GatewayAdminFacadeController.class, "overview", GatewayErrorCode.DOWNSTREAM_ADMIN_UNAVAILABLE, overviewSwallowed));
    }

    @Test
    void 운영_기본_설정에서는_명세_엔드포인트를_끈다() {
        ApiDocsVerification.assertApiDocsDisabledByDefault();
    }

    @Test
    void 스키마_이름이_겹치지_않는다() {
        verification.assertUniqueSchemaNames();
    }

    @Test
    void 명세의_필드_이름이_실제_JSON과_같다() {
        verification.assertSchemaPropertiesMatchJson(jsonMapper::writeValueAsString);
    }

    // 관리자 로그인 상태는 Map으로 만들므로, 문서용 스키마(AdminAuthStatus)의 필드가 실제 응답 키와 같은지 직접 확인한다
    @Test
    void 관리자_로그인_상태_문서가_실제_응답_키와_같다() throws Exception {
        String body = mockMvc.perform(get("/admin/api/auth/status")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Set<String> actual = new TreeSet<>();
        jsonMapper.readTree(body).path("success").propertyNames().forEach(actual::add);
        JsonNode schema = jsonMapper.readTree(verification.spec()).path("components").path("schemas").path("AdminAuthStatus").path("properties");
        Set<String> documented = new TreeSet<>();
        schema.propertyNames().forEach(documented::add);
        assertThat(documented).isEqualTo(actual);
    }
}
