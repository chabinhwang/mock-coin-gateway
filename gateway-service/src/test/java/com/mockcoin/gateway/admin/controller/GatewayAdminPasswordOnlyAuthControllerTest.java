package com.mockcoin.gateway.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mockcoin.gateway.admin.config.GatewayAdminProperties;
import com.mockcoin.gateway.admin.domain.AdminCredentialRecord;
import com.mockcoin.gateway.admin.integration.GatewayAdminCredentialRepository;
import com.yubico.webauthn.data.ByteArray;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayAdminPasswordOnlyAuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GatewayAdminCredentialRepository credentialRepository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("mockcoin.admin.enabled", () -> true);
        registry.add("mockcoin.admin.username", () -> "admin");
        registry.add("mockcoin.admin.password", () -> "secret");
        registry.add("mockcoin.admin.passkey-enabled", () -> false);
        registry.add("mockcoin.security.ip-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-auth-requests-per-window", () -> 1000);
        registry.add("mockcoin.security.admin-read-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-write-requests-per-minute", () -> 1000);
        registry.add("mockcoin.security.admin-sensitive-requests-per-minute", () -> 1000);
    }

    @Test
    void passwordLoginCreatesFullAdminSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.adminSession").value(true))
                .andExpect(jsonPath("$.success.registrationRequired").value(false))
                .andReturn();

        mockMvc.perform(get("/admin/index.html").cookie(sessionCookie(result)))
                .andExpect(status().isOk());
    }

    @Test
    void statusReportsPasskeyDisabled() throws Exception {
        mockMvc.perform(get("/admin/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.passkeyEnabled").value(false))
                .andExpect(jsonPath("$.success.registrationRequired").value(false));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void leftoverPasskeyDoesNotBlockPasswordLogin() throws Exception {
        credentialRepository.save(new AdminCredentialRecord(
                "admin",
                new ByteArray(new byte[]{1}),
                new ByteArray(new byte[]{2}),
                new ByteArray(new byte[]{3}),
                0L));

        mockMvc.perform(post("/admin/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.adminSession").value(true));
    }

    @Test
    void webauthnEndpointsStayDisabled() throws Exception {
        mockMvc.perform(post("/admin/api/auth/webauthn/login/options"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.errorCode").value("PASSKEY_DISABLED"));

        mockMvc.perform(post("/admin/api/auth/webauthn/register/options"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.errorCode").value("PASSKEY_DISABLED"));

        mockMvc.perform(post("/admin/api/auth/webauthn/login/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"c\",\"response\":\"{}\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.errorCode").value("PASSKEY_DISABLED"));

        mockMvc.perform(post("/admin/api/auth/webauthn/register/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"c\",\"response\":\"{}\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.errorCode").value("PASSKEY_DISABLED"));
    }

    private static Cookie sessionCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotBlank();
        String value = setCookie.substring(
                (GatewayAdminProperties.SESSION_COOKIE_NAME + "=").length(),
                setCookie.indexOf(';'));
        return new Cookie(GatewayAdminProperties.SESSION_COOKIE_NAME, value);
    }
}
