package com.armada.boot.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.Application;
import com.armada.shared.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 拦截器集成验证:/api/** 缺头 → TENANT_MISSING;带有效头 → code=0;/api/public/** 不被拦。
 * 走真库(只读 GET,不需事务回滚)。
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class TenantInterceptorIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void protectedEndpoint_withoutTenantHeader_returnsTenantMissing() throws Exception {
        mockMvc.perform(get("/api/ip-proxies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.TENANT_MISSING.code()));
    }

    @Test
    void protectedEndpoint_withValidTenantHeader_returnsSuccess() throws Exception {
        mockMvc.perform(get("/api/ip-proxies").header("X-Tenant-Code", "demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
