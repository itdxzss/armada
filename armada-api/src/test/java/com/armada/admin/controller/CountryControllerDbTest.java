package com.armada.admin.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class CountryControllerDbTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void options_ipScopeReturnsMixedPlusSeededCountries() throws Exception {
        mockMvc.perform(get("/api/admin/countries/options")
                        .param("scope", "ip")
                        .header("X-Tenant-Code", "demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rows.length()").value(249))
                .andExpect(jsonPath("$.data.rows[0].value").value("MIXED"))
                .andExpect(jsonPath("$.data.rows[0].nameZh").value("混合（不限国家）"))
                .andExpect(jsonPath("$.data.rows[0].virtual").value(true))
                .andExpect(jsonPath("$.data.rows[1].value").value("AF"))
                .andExpect(jsonPath("$.data.rows[1].nameZh").value("阿富汗"));
    }
}
