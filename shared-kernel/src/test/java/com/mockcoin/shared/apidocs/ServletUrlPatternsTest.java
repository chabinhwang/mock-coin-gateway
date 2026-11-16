package com.mockcoin.shared.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ServletUrlPatternsTest {

    @Test
    void 접두사_패턴은_자신과_하위_경로에_걸린다() {
        assertThat(ServletUrlPatterns.matches("/internal/admin/*", "/internal/admin")).isTrue();
        assertThat(ServletUrlPatterns.matches("/internal/admin/*", "/internal/admin/users/{id}")).isTrue();
        assertThat(ServletUrlPatterns.matches("/internal/admin/*", "/internal/administrator")).isFalse();
    }

    @Test
    void 정확_패턴은_같은_경로에만_걸린다() {
        assertThat(ServletUrlPatterns.matches("/internal/candles", "/internal/candles")).isTrue();
        assertThat(ServletUrlPatterns.matches("/internal/candles", "/internal/candles/batch")).isFalse();
    }

    @Test
    void 전체_패턴은_모든_경로에_걸린다() {
        assertThat(ServletUrlPatterns.matches("/*", "/anything")).isTrue();
        assertThat(ServletUrlPatterns.anyMatches(List.of("/a", "/b/*"), "/b/c")).isTrue();
        assertThat(ServletUrlPatterns.anyMatches(List.of("/a", "/b/*"), "/c")).isFalse();
    }
}
