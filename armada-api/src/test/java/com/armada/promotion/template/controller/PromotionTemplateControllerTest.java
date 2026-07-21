package com.armada.promotion.template.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.web.GlobalExceptionHandler;
import com.armada.promotion.template.model.dto.PromotionTemplateQuery;
import com.armada.promotion.template.model.vo.PromotionTemplateSupportedParamVO;
import com.armada.promotion.template.model.vo.PromotionTemplateVO;
import com.armada.promotion.template.service.PromotionTemplateService;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PromotionTemplateControllerTest {

    @Mock
    private PromotionTemplateService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PromotionTemplateController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void queryReturnsPageFieldsWithoutAcceptingTenantParameter() throws Exception {
        when(service.page(any())).thenReturn(PageResult.of(List.of(template()), 2, 20, 5));

        mockMvc.perform(get("/api/promotion-templates/query")
                        .param("page", "2")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.list[0].id").value(130))
                .andExpect(jsonPath("$.data.list[0].templateCode").value("base_sex2"))
                .andExpect(jsonPath("$.data.list[0].subaccountVisible").value(true))
                .andExpect(jsonPath("$.data.list[0].supportedParams[0].code").value("themeColor"))
                .andExpect(jsonPath("$.data.list[0].supportedParams[0].label").value("主题色"))
                .andExpect(jsonPath("$.data.list[0].tenantId").doesNotExist());

        ArgumentCaptor<PromotionTemplateQuery> captor = ArgumentCaptor.forClass(PromotionTemplateQuery.class);
        verify(service).page(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPage()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    private static PromotionTemplateVO template() {
        return new PromotionTemplateVO(
                130L,
                "base_sex2",
                "约会二代",
                "/preview/base_sex2.png",
                true,
                List.of(new PromotionTemplateSupportedParamVO("themeColor", "主题色")),
                null,
                1782310803000L,
                1784254511000L);
    }
}
