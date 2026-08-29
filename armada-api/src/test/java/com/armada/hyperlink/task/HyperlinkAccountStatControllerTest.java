package com.armada.hyperlink.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.web.GlobalExceptionHandler;
import com.armada.hyperlink.task.controller.HyperlinkTaskAccountStatController;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountStatItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportJobVO;
import com.armada.hyperlink.task.service.HyperlinkAccountStatQueryService;
import com.armada.hyperlink.task.service.HyperlinkAccountStatExportService;
import com.armada.shared.response.PageResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** H5 HTTP 路径、参数绑定和 202 异步导出合同。 */
class HyperlinkAccountStatControllerTest {

    private HyperlinkAccountStatQueryService queryService;
    private HyperlinkAccountStatExportService exportService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(HyperlinkAccountStatQueryService.class);
        exportService = mock(HyperlinkAccountStatExportService.class);
        HyperlinkTaskAccountStatController controller =
                new HyperlinkTaskAccountStatController(queryService, exportService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void bindsEveryAccountStatQueryField() throws Exception {
        when(queryService.list(anyLong(), any(HyperlinkAccountStatQuery.class)))
                .thenReturn(PageResult.of(List.of(new HyperlinkAccountStatItemVO(
                        0, null, null, null, null, new BigDecimal("0.0"),
                        0, 0, 1, null)), 2, 20, 1));

        mockMvc.perform(get("/api/hyperlink-tasks/88/account-stats")
                        .param("page", "2")
                        .param("pageSize", "20")
                        .param("startAt", "1000")
                        .param("endAt", "2000")
                        .param("senderCountryIso2", "BR")
                        .param("successRateMin", "70")
                        .param("successRateMax", "90")
                        .param("sortField", "failedNum")
                        .param("sortOrder", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].bucketKey").value(0))
                .andExpect(jsonPath("$.data.page").value(2));

        ArgumentCaptor<HyperlinkAccountStatQuery> captor =
                ArgumentCaptor.forClass(HyperlinkAccountStatQuery.class);
        verify(queryService).list(org.mockito.ArgumentMatchers.eq(88L), captor.capture());
        HyperlinkAccountStatQuery query = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(query.getSenderCountryIso2()).isEqualTo("BR");
        org.assertj.core.api.Assertions.assertThat(query.getSuccessRateMin())
                .isEqualByComparingTo("70");
        org.assertj.core.api.Assertions.assertThat(query.getSortField()).isEqualTo("failedNum");
    }

    @Test
    void createsAsyncCsvJobWithHttp202() throws Exception {
        when(exportService.createAccountStatsJob(
                org.mockito.ArgumentMatchers.eq(88L), any(HyperlinkAccountStatFilterDTO.class), isNull()))
                .thenReturn(new HyperlinkTaskExportJobVO(
                        9L, "ACCOUNT_STATS", "PENDING", 100L, null,
                        0, null, 100L, null, false));

        mockMvc.perform(post("/api/hyperlink-tasks/88/account-stats/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"senderCountryIso2":"US","successRateMin":50,
                                 "sortField":"successNum","sortOrder":"desc"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.exportType").value("ACCOUNT_STATS"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }
}
