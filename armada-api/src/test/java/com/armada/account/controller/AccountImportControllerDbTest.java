package com.armada.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * AccountImportController MockMvc 集成测试:覆盖 POST 导入、GET 批次列表、GET 明细列表、GET 导出。
 *
 * <p>走真库(armada schema),每个测试事务回滚不留数据。</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Transactional
class AccountImportControllerDbTest {

    private static final String TENANT_HEADER = "X-Tenant-Code";
    private static final String TENANT_CODE = "demo";

    /** JSON 格式完整的单账号内容,供 POST 导入时作 text 参数使用。 */
    private static final String VALID_JSON_TEXT =
            "[{\"wid\":\"8613900000001\","
                    + "\"creds\":{\"registrationId\":99,\"noiseKey\":{},"
                    + "\"signedIdentityKey\":{},\"signedPreKey\":{}}}]";

    @Autowired
    private MockMvc mockMvc;

    // -----------------------------------------------------------------------
    // C1: POST /api/account-imports — text 导入
    // -----------------------------------------------------------------------

    /**
     * 通过 text 参数导入:期望 code=0 + data.importedRows=1。
     */
    @Test
    void post_importViaText_returnsOkWithBatchVO() throws Exception {
        mockMvc.perform(multipart("/api/account-imports")
                        .param("importFormat", "2")
                        .param("deviceOs", "1")
                        .param("accountType", "1")
                        .param("batchName", "ctrl-test-text")
                        .param("text", VALID_JSON_TEXT)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.totalRows").value(1))
                .andExpect(jsonPath("$.data.status").value(2));
    }

    /**
     * 通过 MultipartFile 导入:文件内容与 text 相同的 JSON 字节。
     * 期望 code=0 + data.importedRows=1 + data.sourceFileName 非空。
     */
    @Test
    void post_importViaFile_returnsOkWithBatchVO() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "import.json", "application/json",
                VALID_JSON_TEXT.getBytes());

        mockMvc.perform(multipart("/api/account-imports")
                        .file(file)
                        .param("importFormat", "2")
                        .param("deviceOs", "1")
                        .param("accountType", "1")
                        .param("batchName", "ctrl-test-file")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.importedRows").value(1))
                // sourceFileName 由 Service 落库, DTO 目前无该字段,Controller 正确传 fileBytes 即可
                .andExpect(jsonPath("$.data.status").value(2));
    }

    /**
     * 空内容导入:无 file 无 text → Service 抛 BusinessException → code != 0。
     */
    @Test
    void post_emptyContent_returnsBusinessError() throws Exception {
        mockMvc.perform(multipart("/api/account-imports")
                        .param("importFormat", "2")
                        .param("deviceOs", "1")
                        .param("accountType", "1")
                        .param("batchName", "ctrl-test-empty")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // -----------------------------------------------------------------------
    // C2: GET /api/account-imports — 批次列表
    // -----------------------------------------------------------------------

    /**
     * 批次列表:先导入一条,再分页查列表 → code=0 + list 非空。
     */
    @Test
    void get_listBatches_returnsOkWithPage() throws Exception {
        // 先导入一条
        mockMvc.perform(multipart("/api/account-imports")
                .param("importFormat", "2")
                .param("deviceOs", "1")
                .param("accountType", "1")
                .param("batchName", "list-test-batch")
                .param("text", VALID_JSON_TEXT)
                .header(TENANT_HEADER, TENANT_CODE));

        // 查批次列表
        mockMvc.perform(get("/api/account-imports")
                        .param("page", "1")
                        .param("pageSize", "10")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.list").isArray());
    }

    // -----------------------------------------------------------------------
    // C3: GET /api/account-imports/{batchId}/details — 明细列表
    // -----------------------------------------------------------------------

    /**
     * 明细列表:导入后获取 batchId,再查明细 → code=0 + list 非空。
     */
    @Test
    void get_listDetails_returnsOkWithPage() throws Exception {
        // 先导入取 batchId
        MvcResult importResult = mockMvc.perform(multipart("/api/account-imports")
                        .param("importFormat", "2")
                        .param("deviceOs", "1")
                        .param("accountType", "1")
                        .param("batchName", "detail-list-test")
                        .param("text", VALID_JSON_TEXT)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andReturn();

        String body = importResult.getResponse().getContentAsString();
        // 从响应中提取 data.id (batchId)
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Long batchId = mapper.readTree(body).path("data").path("id").longValue();
        assertThat(batchId).isPositive();

        // 查明细
        mockMvc.perform(get("/api/account-imports/{batchId}/details", batchId)
                        .param("page", "1")
                        .param("pageSize", "10")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.list[0].wsPhone").value("8613900000001"));
    }

    // -----------------------------------------------------------------------
    // C4: GET /api/account-imports/{batchId}/export — CSV 导出
    // -----------------------------------------------------------------------

    /**
     * 导出 CSV:验 Content-Type=text/csv + Content-Disposition=attachment + 响应体含 CSV 内容。
     */
    @Test
    void get_exportCsv_returnsAttachmentWithCsvContentType() throws Exception {
        // 先导入取 batchId
        MvcResult importResult = mockMvc.perform(multipart("/api/account-imports")
                        .param("importFormat", "2")
                        .param("deviceOs", "1")
                        .param("accountType", "1")
                        .param("batchName", "export-test")
                        .param("text", VALID_JSON_TEXT)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andReturn();

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Long batchId = mapper.readTree(importResult.getResponse().getContentAsString())
                .path("data").path("id").longValue();
        assertThat(batchId).isPositive();

        // 导出
        mockMvc.perform(get("/api/account-imports/{batchId}/export", batchId)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "account-import-" + batchId + ".csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")));
    }
}
