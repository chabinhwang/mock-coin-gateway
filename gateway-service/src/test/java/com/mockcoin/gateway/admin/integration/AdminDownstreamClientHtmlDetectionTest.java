package com.mockcoin.gateway.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AdminDownstreamClientHtmlDetectionTest {
    @Test
    void detectsHtmlByDoctypeOrHtmlPrefixOnly() {
        assertThat(AdminDownstreamClient.looksLikeHtml(new byte[0])).isFalse();
        assertThat(AdminDownstreamClient.looksLikeHtml("<!DOCTYPE html><html></html>".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(AdminDownstreamClient.looksLikeHtml(("\uFEFF<!DOCTYPE html><html></html>").getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(AdminDownstreamClient.looksLikeHtml("<html><body>error</body></html>".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(AdminDownstreamClient.looksLikeHtml("{\"resultType\":\"SUCCESS\"}".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void wildcardContentTypeMustNotMakeJsonLookLikeHtml() {
        assertThat(MediaType.TEXT_HTML.isCompatibleWith(MediaType.ALL)).isTrue();
        assertThat(AdminDownstreamClient.looksLikeHtml("{\"items\":[]}".getBytes(StandardCharsets.UTF_8))).isFalse();
    }
}
