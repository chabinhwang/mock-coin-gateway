package com.mockcoin.shared.apidocs;

import java.util.Collection;

// 서블릿 필터 URL 패턴(정확 일치 또는 "/prefix/*")이 API 경로에 적용되는지 판단한다.
// 서블릿 스펙상 "/prefix/*"는 "/prefix" 자신과 그 아래 모든 경로에 걸린다.
final class ServletUrlPatterns {
    private ServletUrlPatterns() {
    }

    // 패턴 중 하나라도 경로에 적용되는지 확인한다.
    static boolean anyMatches(Collection<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> matches(pattern, path));
    }

    // "/*"는 모든 경로, "/prefix/*"는 prefix 자신과 하위 경로, 그 외는 정확히 같은 경로에 적용되는지 확인한다.
    static boolean matches(String pattern, String path) {
        if ("/*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return pattern.equals(path);
    }
}
