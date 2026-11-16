package com.mockcoin.gateway.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import com.mockcoin.gateway.admin.integration.GatewayAdminCredentialRepository;
import com.mockcoin.gateway.admin.service.GatewayAdminAuthService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yubico.webauthn.data.ByteArray;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayAdminAuthControllerTest {
    private static final TestAdminBackend BACKEND = TestAdminBackend.start();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GatewayAdminAuthService authService;

    @Autowired
    private GatewayAdminCredentialRepository credentialRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mockcoin.admin.enabled", () -> true);
        registry.add("mockcoin.routes.user-service", () -> BACKEND.uri().toString());
        registry.add("mockcoin.routes.trading-service", () -> "http://127.0.0.1:1");
        registry.add("mockcoin.routes.market-service", () -> "http://127.0.0.1:1");
        registry.add("mockcoin.admin.internal-token", () -> "gateway-admin-token");
        registry.add("mockcoin.admin.user-service-token", () -> "user-admin-token");
        registry.add("mockcoin.admin.trading-service-token", () -> "trading-admin-token");
        registry.add("mockcoin.admin.username", () -> "admin");
        registry.add("mockcoin.admin.password", () -> "secret");
        registry.add("mockcoin.admin.passkey-enabled", () -> true);
        registry.add("mockcoin.admin.webauthn-rp-id", () -> "localhost");
        registry.add("mockcoin.admin.webauthn-origins", () -> "http://localhost");
        registry.add("mockcoin.security.ip-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-auth-requests-per-window", () -> 1000);
        registry.add("mockcoin.security.admin-read-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-write-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-sensitive-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.trusted-proxy-cidrs", () -> "127.0.0.1/32");
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop();
    }

    @Test
    void adminRootRedirectsToStaticConsole() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/index.html"));
    }

    @Test
    void servesAdminLoginStaticAsset() throws Exception {
        mockMvc.perform(get("/admin/login.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MockCoin Admin Login")));
    }

    @Test
    void servesAdminFaviconWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/admin/favicon.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
    }

    @Test
    void passwordLoginCreatesHttpOnlyAdminSessionCookie() throws Exception {
        MvcResult result = login();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains(GatewayAdminProperties.SESSION_COOKIE_NAME + "=")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/admin");
    }

    @Test
    void adminIndexRequiresFullAdminSession() throws Exception {
        mockMvc.perform(get("/admin/index.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login.html"));
    }

    @Test
    void invalidPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/admin/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.errorCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void passwordReregisterIsRejectedWhenPasskeyAlreadyExists() throws Exception {
        credentialRepository.save(new AdminCredentialRecord(
                "admin",
                new ByteArray(new byte[]{1}),
                new ByteArray(new byte[]{2}),
                new ByteArray(new byte[]{3}),
                0L));

        mockMvc.perform(post("/admin/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\",\"reRegister\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.errorCode").value("PASSKEY_REQUIRED"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void staleBootstrapRegistrationSessionCannotRegisterAfterPasskeyExists() throws Exception {
        Cookie staleRegistrationCookie = sessionCookie(login());
        credentialRepository.save(new AdminCredentialRecord(
                "admin",
                new ByteArray(new byte[]{4}),
                new ByteArray(new byte[]{5}),
                new ByteArray(new byte[]{6}),
                0L));

        mockMvc.perform(post("/admin/api/auth/webauthn/register/options").cookie(staleRegistrationCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void adminSessionCanStartPasskeyRotationAfterPasskeyExists() throws Exception {
        credentialRepository.save(new AdminCredentialRecord(
                "admin",
                new ByteArray(new byte[]{7}),
                new ByteArray(new byte[]{8}),
                new ByteArray(new byte[]{9}),
                0L));

        mockMvc.perform(post("/admin/api/auth/webauthn/register/options").cookie(adminSessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.challengeId").isNotEmpty());
    }

    @Test
    void registrationFinishWithoutChallengeIdKeepsDomainFailureContract() throws Exception {
        Cookie registrationCookie = sessionCookie(login());

        mockMvc.perform(post("/admin/api/auth/webauthn/register/finish")
                        .cookie(registrationCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"{}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.errorCode").value("WEBAUTHN_REGISTRATION_FAILED"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void loginFinishWithoutChallengeIdKeepsDomainFailureContract() throws Exception {
        credentialRepository.save(new AdminCredentialRecord(
                "admin",
                new ByteArray(new byte[]{10}),
                new ByteArray(new byte[]{11}),
                new ByteArray(new byte[]{12}),
                0L));

        mockMvc.perform(post("/admin/api/auth/webauthn/login/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"{}\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.errorCode").value("WEBAUTHN_LOGIN_FAILED"));
    }

    @Test
    void trustedProxyUsesForwardedClientIpForIndependentAdminLockouts() throws Exception {
        String lockedClientIp = "198.51.100.40";
        for (int attempt = 0; attempt < GatewayAdminProperties.MAX_LOGIN_ATTEMPTS; attempt++) {
            invalidLoginFromForwardedIp(lockedClientIp);
        }

        mockMvc.perform(get("/admin/api/auth/status")
                        .header("X-Forwarded-For", lockedClientIp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.isLocked").value(true));

        mockMvc.perform(get("/admin/api/auth/status")
                        .header("X-Forwarded-For", "198.51.100.41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.isLocked").value(false));
    }

    @Test
    void passwordLoginOnlyCreatesRegistrationSessionAndCannotAccessFacade() throws Exception {
        Cookie cookie = sessionCookie(login());

        mockMvc.perform(get("/admin/api/v1/admin-console/billing/products").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void adminSessionCookieAllowsFacadeWithoutGatewayToken() throws Exception {
        Cookie cookie = adminSessionCookie();
        BACKEND.lastToken.set(null);

        mockMvc.perform(get("/admin/api/v1/admin-console/billing/products").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.source").value("user"));

        assertThat(BACKEND.lastToken.get()).isEqualTo("user-admin-token");
    }

    @Test
    void cookieAdminMutationRejectsInvalidOrigin() throws Exception {
        Cookie cookie = adminSessionCookie();

        mockMvc.perform(post("/admin/api/v1/admin-console/users/{userId}/reset-passes", 7)
                        .cookie(cookie)
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void tokenAdminMutationBypassesCookieOriginGuardForAutomation() throws Exception {
        mockMvc.perform(post("/admin/api/v1/admin-console/billing/products")
                        .header("X-Mockcoin-Admin-Token", "gateway-admin-token")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerSku\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.source").value("user"));
    }

    @Test
    void logoutDeletesSessionCookieAndInvalidatesSession() throws Exception {
        Cookie cookie = adminSessionCookie();

        mockMvc.perform(post("/admin/api/auth/logout").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

        mockMvc.perform(get("/admin/api/v1/admin-console/billing/products").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.errorCode").value("UNAUTHORIZED"));
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/admin/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.adminSession").value(false))
                .andExpect(jsonPath("$.success.registrationRequired").value(true))
                .andReturn();
    }

    private void invalidLoginFromForwardedIp(String forwardedIp) throws Exception {
        mockMvc.perform(post("/admin/api/auth/login")
                        .header("X-Forwarded-For", forwardedIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.errorCode").value("INVALID_CREDENTIALS"));
    }

    private static Cookie sessionCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotBlank();
        String value = setCookie.substring((GatewayAdminProperties.SESSION_COOKIE_NAME + "=").length(), setCookie.indexOf(';'));
        return new Cookie(GatewayAdminProperties.SESSION_COOKIE_NAME, value);
    }

    private Cookie adminSessionCookie() {
        return new Cookie(GatewayAdminProperties.SESSION_COOKIE_NAME, authService.createAdminSession());
    }

    private static final class TestAdminBackend {
        private final HttpServer server;
        private final AtomicReference<String> lastToken = new AtomicReference<>();

        private TestAdminBackend(HttpServer server) {
            this.server = server;
        }

        static TestAdminBackend start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
                TestAdminBackend backend = new TestAdminBackend(server);
                server.createContext("/internal/user-service/admin/billing/products", backend::handleProducts);
                server.setExecutor(Executors.newSingleThreadExecutor());
                server.start();
                return backend;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to start admin test backend", exception);
            }
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        void stop() {
            server.stop(0);
        }

        private void handleProducts(HttpExchange exchange) throws IOException {
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Mockcoin-Admin-Token"));
            byte[] bytes = "{\"resultType\":\"SUCCESS\",\"success\":{\"source\":\"user\"},\"error\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }
    }
}
