package com.mockcoin.gateway.admin.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// downstream admin 조회 경로에 쿼리 파라미터를 form 인코딩으로 붙인다 (빈 값은 생략, '+'·'%'·한글 보존)
public final class AdminQueryString {
    private final String path;
    private final List<String> pairs = new ArrayList<>();

    // 쿼리 파라미터를 붙일 기준 경로를 받는다.
    private AdminQueryString(String path) {
        this.path = path;
    }

    public static AdminQueryString path(String path) {
        return new AdminQueryString(path);
    }

    // 값이 null이거나 공백이면 건너뛰고, 아니면 앞뒤 공백을 뺀 값을 form 인코딩해 파라미터로 추가한다.
    public AdminQueryString param(String name, Object value) {
        if (value == null) {
            return this;
        }
        String text = value.toString();
        if (text.isBlank()) {
            return this;
        }
        pairs.add(encode(name) + "=" + encode(text.strip()));
        return this;
    }

    // 파라미터가 있으면 경로 뒤에 '?'와 '&'로 이어 붙이고, 없으면 경로만 반환한다.
    public String build() {
        return pairs.isEmpty() ? path : path + "?" + String.join("&", pairs);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
