package com.mockcoin.gateway.admin.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayAdminDisabledByDefaultTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminUiAndApiAreNotRegisteredByDefault() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/index.html"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/api/auth/status"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/admin/api/v1/admin-console/overview"))
                .andExpect(status().isNotFound());
    }
}
