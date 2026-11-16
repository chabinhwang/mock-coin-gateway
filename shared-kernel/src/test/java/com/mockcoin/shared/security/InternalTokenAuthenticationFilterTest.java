package com.mockcoin.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * `/internal/**` 전 구간의 fail-closed 경계를 고정한다.
 *
 * <p>trading-service와 market-service는 자체 SecurityFilterChain이 없어, 이 필터와
 * gateway의 헤더 strip/inject가 내부 API 보안 경계의 전부다. 여기서 통과시키면
 * 다른 어떤 계층도 막아주지 않으므로, 거부 경로를 특히 촘촘하게 고정한다.
 */
class InternalTokenAuthenticationFilterTest {
    private static final String HEADER = "X-Mockcoin-Internal-Token";
    private static final String CONFIGURED_TOKEN = "s3cr3t-internal-token";
    private static final String NOT_CONFIGURED_REASON = "내부 토큰이 설정되지 않았습니다.";

    private final RecordingFilterChain chain = new RecordingFilterChain();

    // ---------------------------------------------------------------- 허용 경로

    @Test
    void 토큰이_일치하면_체인을_계속_진행한다() throws Exception {
        MockHttpServletResponse response = invoke(filterWith(CONFIGURED_TOKEN), requestWithToken(CONFIGURED_TOKEN));

        assertThat(chain.invocations()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void 헤더_이름은_대소문자를_구분하지_않는다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-mockcoin-internal-token", CONFIGURED_TOKEN);

        invoke(filterWith(CONFIGURED_TOKEN), request);

        assertThat(chain.invocations()).isEqualTo(1);
    }

    @Test
    void 토큰_값은_매_요청마다_supplier에서_다시_읽는다() throws Exception {
        // 런타임에 토큰이 회전(rotate)되면 이후 요청부터 새 토큰이 적용돼야 한다.
        AtomicReference<String> current = new AtomicReference<>("first-token");
        InternalTokenAuthenticationFilter filter =
                new InternalTokenAuthenticationFilter(HEADER, current::get, NOT_CONFIGURED_REASON);

        invoke(filter, requestWithToken("first-token"));
        assertThat(chain.invocations()).isEqualTo(1);

        current.set("second-token");

        MockHttpServletResponse staleResponse = invoke(filter, requestWithToken("first-token"));
        assertThat(staleResponse.getStatus()).isEqualTo(401);
        assertThat(chain.invocations()).isEqualTo(1);

        invoke(filter, requestWithToken("second-token"));
        assertThat(chain.invocations()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- 거부 경로

    @Test
    void 헤더가_없으면_401이고_체인은_호출되지_않는다() throws Exception {
        MockHttpServletResponse response = invoke(filterWith(CONFIGURED_TOKEN), new MockHttpServletRequest());

        assertThat(chain.invocations()).isZero();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("\"resultType\":\"FAIL\"")
                .contains("\"errorCode\":\"UNAUTHORIZED\"")
                .contains("인증이 필요합니다.");
    }

    @Test
    void 토큰이_틀리면_401이고_체인은_호출되지_않는다() throws Exception {
        MockHttpServletResponse response = invoke(filterWith(CONFIGURED_TOKEN), requestWithToken("wrong-token"));

        assertThat(chain.invocations()).isZero();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @ParameterizedTest(name = "거부되는 헤더 값: [{0}]")
    @ValueSource(strings = {
            "",                            // 빈 문자열
            " s3cr3t-internal-token",      // 앞 공백 — trim하지 않는다
            "s3cr3t-internal-token ",      // 뒤 공백
            "S3CR3T-INTERNAL-TOKEN",       // 대문자 — 대소문자를 구분한다
            "s3cr3t-internal-toke",        // 접두사 일치(prefix)만으로는 통과 못 한다
            "s3cr3t-internal-tokenX",      // 접두사 + 추가 문자
    })
    void 정확히_일치하지_않는_토큰은_모두_거부한다(String headerValue) throws Exception {
        MockHttpServletResponse response = invoke(filterWith(CONFIGURED_TOKEN), requestWithToken(headerValue));

        assertThat(chain.invocations()).isZero();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @ParameterizedTest(name = "설정 토큰이 [{0}]이면 fail-closed")
    @NullSource
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    void 서버측_토큰이_비어있으면_어떤_요청도_통과시키지_않는다(String configuredToken) throws Exception {
        // fail-open이면 내부 API 전체가 무인증으로 열린다. 설정 누락은 반드시 거부여야 한다.
        InternalTokenAuthenticationFilter filter = filterWith(configuredToken);

        MockHttpServletResponse withHeader = invoke(filter, requestWithToken("anything"));
        MockHttpServletResponse withoutHeader = invoke(filter, new MockHttpServletRequest());

        assertThat(chain.invocations()).isZero();
        assertThat(withHeader.getStatus()).isEqualTo(401);
        assertThat(withoutHeader.getStatus()).isEqualTo(401);
        assertThat(withHeader.getContentAsString()).contains(NOT_CONFIGURED_REASON);
    }

    @Test
    void 설정_누락_사유와_인증_실패_사유를_구분해서_노출한다() throws Exception {
        // 운영자가 "설정을 안 했다"와 "호출자가 틀렸다"를 로그/응답에서 구분할 수 있어야 한다.
        MockHttpServletResponse notConfigured = invoke(filterWith(""), requestWithToken(CONFIGURED_TOKEN));
        MockHttpServletResponse badToken = invoke(filterWith(CONFIGURED_TOKEN), requestWithToken("nope"));

        assertThat(notConfigured.getContentAsString()).contains(NOT_CONFIGURED_REASON);
        assertThat(badToken.getContentAsString()).contains("인증이 필요합니다.").doesNotContain(NOT_CONFIGURED_REASON);
    }

    @Test
    void 토큰_비교는_길이가_달라도_동일하게_거부한다() throws Exception {
        // 비교는 MessageDigest.isEqual 기반이라 일치하는 접두사 길이가 판정에 영향을 주지 않는다.
        // (타이밍 자체는 단위 테스트로 확정할 수 없어, 여기서는 판정 결과만 고정한다.)
        InternalTokenAuthenticationFilter filter = filterWith(CONFIGURED_TOKEN);

        for (String candidate : new String[]{"s", "s3c", "s3cr3t-internal", CONFIGURED_TOKEN + "-extra"}) {
            MockHttpServletResponse response = invoke(filter, requestWithToken(candidate));
            assertThat(response.getStatus())
                    .as("접두사 [%s]는 통과하면 안 된다", candidate)
                    .isEqualTo(401);
        }
        assertThat(chain.invocations()).isZero();
    }

    @Test
    void 멀티바이트_토큰도_정확히_비교한다() throws Exception {
        // UTF-8 바이트 비교로 바뀌었으므로 비ASCII 토큰이 깨지지 않아야 한다.
        String koreanToken = "내부-토큰-값";
        InternalTokenAuthenticationFilter filter = filterWith(koreanToken);

        invoke(filter, requestWithToken(koreanToken));
        assertThat(chain.invocations()).isEqualTo(1);

        MockHttpServletResponse denied = invoke(filter, requestWithToken("내부-토큰-갑"));
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(chain.invocations()).isEqualTo(1);
    }

    @Test
    void 거부_응답이_설정된_토큰_값을_노출하지_않는다() throws Exception {
        MockHttpServletResponse response = invoke(filterWith(CONFIGURED_TOKEN), requestWithToken("wrong"));

        assertThat(response.getContentAsString()).doesNotContain(CONFIGURED_TOKEN);
    }

    // ---------------------------------------------------------------- 응답 형식

    @Test
    void 거부_응답은_UTF8_JSON_컨텐츠_타입을_설정한다() throws Exception {
        MockHttpServletResponse response = invoke(filterWith(CONFIGURED_TOKEN), new MockHttpServletRequest());

        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
    }

    @Test
    void 거부_사유의_특수문자를_JSON으로_이스케이프한다() throws Exception {
        // 사유 문자열은 설정에서 오므로, 깨진 JSON이 나가면 호출자 파싱이 실패한다.
        String rawControl = String.valueOf((char) 0x01);
        String reason = "quote:\" backslash:\\ newline:\n tab:\t"
                + " backspace:\b formfeed:\f carriage:\r control:" + rawControl;
        InternalTokenAuthenticationFilter filter =
                new InternalTokenAuthenticationFilter(HEADER, () -> "", reason);

        MockHttpServletResponse response = invoke(filter, new MockHttpServletRequest());
        String body = response.getContentAsString();

        assertThat(body)
                .contains("quote:\\\"")
                .contains("backslash:\\\\")
                .contains("newline:\\n")
                .contains("tab:\\t")
                .contains("backspace:\\b")
                .contains("formfeed:\\f")
                .contains("carriage:\\r")
                .contains("control:\\u0001");
        // 원시 제어문자가 그대로 나가면 JSON이 깨진다.
        assertThat(body)
                .doesNotContain("\n").doesNotContain("\t").doesNotContain("\r")
                .doesNotContain("\b").doesNotContain("\f").doesNotContain(rawControl);
    }

    // ---------------------------------------------------------------- 비-HTTP 경로

    @Test
    void HTTP가_아닌_요청은_검증하지_않고_통과시킨다() throws Exception {
        // Servlet 스펙상 도달할 수 없는 조합이지만, 방어 코드의 동작을 명시적으로 고정한다.
        InternalTokenAuthenticationFilter filter = filterWith(CONFIGURED_TOKEN);

        filter.doFilter(mock(ServletRequest.class), mock(ServletResponse.class), chain);

        assertThat(chain.invocations()).isEqualTo(1);
    }

    @Test
    void HTTP_요청이라도_응답이_HTTP가_아니면_통과시킨다() throws Exception {
        InternalTokenAuthenticationFilter filter = filterWith(CONFIGURED_TOKEN);

        filter.doFilter(new MockHttpServletRequest(), mock(ServletResponse.class), chain);

        assertThat(chain.invocations()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 생성자 계약

    @Test
    void 생성자는_null_인자를_거부한다() {
        assertThatThrownBy(() -> new InternalTokenAuthenticationFilter(null, () -> "t", "reason"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("headerName");
        assertThatThrownBy(() -> new InternalTokenAuthenticationFilter(HEADER, null, "reason"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("expectedTokenSupplier");
        assertThatThrownBy(() -> new InternalTokenAuthenticationFilter(HEADER, () -> "t", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("notConfiguredReason");
    }

    @Test
    void API_문서가_읽는_헤더_이름과_미설정_사유를_노출한다() {
        InternalTokenAuthenticationFilter filter = filterWith("t");

        assertThat(filter.headerName()).isEqualTo(HEADER);
        assertThat(filter.notConfiguredReason()).isEqualTo(NOT_CONFIGURED_REASON);
    }

    // ---------------------------------------------------------------- helpers

    private InternalTokenAuthenticationFilter filterWith(String configuredToken) {
        return new InternalTokenAuthenticationFilter(HEADER, () -> configuredToken, NOT_CONFIGURED_REASON);
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, token);
        return request;
    }

    private MockHttpServletResponse invoke(InternalTokenAuthenticationFilter filter, MockHttpServletRequest request)
            throws IOException, ServletException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static final class RecordingFilterChain implements FilterChain {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            invocations.incrementAndGet();
        }

        private int invocations() {
            return invocations.get();
        }
    }
}
