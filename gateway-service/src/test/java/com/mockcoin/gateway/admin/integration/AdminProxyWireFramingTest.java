package com.mockcoin.gateway.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * admin 프록시가 downstream의 chunked 응답을 전달할 때 gateway 응답의 프레이밍 헤더가
 * 중복되지 않는지 실제 Tomcat 위 raw HTTP 응답으로 검증한다.
 * MockMvc는 서블릿 컨테이너를 거치지 않아 Transfer-Encoding 중복을 잡지 못한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminProxyWireFramingTest {

    private static HttpServer downstream;

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void registerRoutes(DynamicPropertyRegistry registry) throws IOException {
        downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/internal/community-service/admin/published-posts", exchange -> {
            byte[] body = bigJson();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            // responseLength = 0 → chunked transfer encoding (큰 JSON을 돌려주는 downstream Tomcat과 같은 방식)
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        downstream.start();
        String downstreamUri = "http://127.0.0.1:" + downstream.getAddress().getPort();

        registry.add("mockcoin.admin.enabled", () -> true);
        registry.add("mockcoin.admin.internal-token", () -> "gateway-admin-token");
        registry.add("mockcoin.admin.community-service-token", () -> "community-admin-token");
        registry.add("mockcoin.routes.community-service", () -> downstreamUri);
        registry.add("mockcoin.websocket.ping-interval-seconds", () -> "31");
    }

    @AfterAll
    static void stopDownstream() {
        if (downstream != null) {
            downstream.stop(0);
        }
    }

    private static byte[] bigJson() {
        StringBuilder sb = new StringBuilder("{\"resultType\":\"SUCCESS\",\"success\":{\"items\":[");
        for (int i = 0; i < 200; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(i)
                    .append(",\"title\":\"게시글 제목 ").append(i).append('\"')
                    .append(",\"content\":\"").append("본문 내용 반복 ".repeat(20)).append("\"}");
        }
        sb.append("]}}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void gatewayResponseMustNotDuplicateFramingHeaders() throws Exception {
        String raw = rawAdminGet("/admin/api/v1/admin-console/community/published-posts?limit=50");

        int headerEnd = raw.indexOf("\r\n\r\n");
        assertThat(headerEnd).isGreaterThan(0);
        String headerBlock = raw.substring(0, headerEnd);
        String body = raw.substring(headerEnd + 4);

        assertThat(headerBlock).startsWith("HTTP/1.1 200");

        long transferEncodingCount = headerBlock.lines()
                .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("transfer-encoding:"))
                .count();
        long contentLengthCount = headerBlock.lines()
                .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                .count();
        // TE 중복은 malformed response라 Cloudflare 등 엄격한 프록시가 520으로 거부한다
        assertThat(transferEncodingCount).isLessThanOrEqualTo(1);
        assertThat(contentLengthCount).isLessThanOrEqualTo(1);
        assertThat(transferEncodingCount + contentLengthCount).isEqualTo(1);

        // byte[] 프록시는 Content-Length identity 프레이밍이어야 한다 (chunk size prefix 없이 본문 시작)
        assertThat(contentLengthCount).isEqualTo(1);
        assertThat(body).startsWith("{\"resultType\":\"SUCCESS\"");
    }

    private String rawAdminGet(String path) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", gatewayPort)) {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + gatewayPort + "\r\n"
                    + "X-Mockcoin-Admin-Token: gateway-admin-token\r\n"
                    + "Accept: application/json\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            StringBuilder raw = new StringBuilder();
            int read;
            while ((read = in.read(buffer)) != -1) {
                raw.append(new String(Arrays.copyOf(buffer, read), StandardCharsets.ISO_8859_1));
            }
            return raw.toString();
        }
    }
}
