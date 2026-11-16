package com.mockcoin.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 모든 서비스가 같은 형태의 응답 봉투를 내보내는지 고정한다.
 *
 * <p>모바일은 `resultType`으로 성공/실패를 분기하므로, 성공에 error가 남거나
 * 실패에 success가 남으면 클라이언트 분기가 깨진다.
 */
class ApiResponseTest {

    @Test
    void success는_SUCCESS_타입에_페이로드를_담고_error를_비운다() {
        ApiResponse<String> response = ApiResponse.success("payload");

        assertThat(response.resultType()).isEqualTo(ApiResponse.ResultType.SUCCESS);
        assertThat(response.success()).isEqualTo("payload");
        assertThat(response.error()).isNull();
    }

    @Test
    void ok는_success의_별칭이다() {
        assertThat(ApiResponse.ok("payload")).isEqualTo(ApiResponse.success("payload"));
    }

    @Test
    void fail은_FAIL_타입에_에러를_담고_success를_비운다() {
        ApiResponse<String> response = ApiResponse.fail("NOT_FOUND", "찾을 수 없습니다.");

        assertThat(response.resultType()).isEqualTo(ApiResponse.ResultType.FAIL);
        assertThat(response.success()).isNull();
        assertThat(response.error()).isEqualTo(new ErrorResponse("NOT_FOUND", "찾을 수 없습니다."));
    }

    @Test
    void 페이로드가_null이어도_SUCCESS_타입은_유지된다() {
        // 본문 없는 200(예: 취소 성공)에서도 클라이언트가 성공으로 분기해야 한다.
        ApiResponse<Void> response = ApiResponse.success(null);

        assertThat(response.resultType()).isEqualTo(ApiResponse.ResultType.SUCCESS);
        assertThat(response.error()).isNull();
    }

    @Test
    void 컬렉션_페이로드도_그대로_보존한다() {
        List<String> items = List.of("a", "b");

        assertThat(ApiResponse.success(items).success()).containsExactly("a", "b");
    }

    @Test
    void ResultType은_SUCCESS와_FAIL_두_값만_가진다() {
        // 값이 늘어나면 모바일 분기 로직도 함께 바뀌어야 한다.
        assertThat(ApiResponse.ResultType.values())
                .containsExactly(ApiResponse.ResultType.SUCCESS, ApiResponse.ResultType.FAIL);
    }

    @Test
    void ResultType_이름이_직렬화_계약이므로_고정한다() {
        assertThat(ApiResponse.ResultType.SUCCESS.name()).isEqualTo("SUCCESS");
        assertThat(ApiResponse.ResultType.FAIL.name()).isEqualTo("FAIL");
    }
}
