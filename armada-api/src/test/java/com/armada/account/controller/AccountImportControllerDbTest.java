package com.armada.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.Application;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
                    + "\"registrationId\":99,\"noiseKey\":{},"
                    + "\"signedIdentityKey\":{},\"signedPreKey\":{}}]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

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
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.sourceFileName").value("import.json"))  // 文件导入 sourceFileName 非空
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

    @Test
    void get_listBatches_filterByLoginResult() throws Exception {
        long suffix = System.currentTimeMillis();
        Long failedBatchId = importFileBatch(
                "login-filter-" + suffix + "-failed.json", "8613900100001");
        Long cleanBatchId = importFileBatch(
                "login-filter-" + suffix + "-clean.json", "8613900100002");
        settleImportLoginResult(failedBatchId, 2);
        settleImportLoginResult(cleanBatchId, 1);

        mockMvc.perform(get("/api/account-imports")
                        .param("page", "1")
                        .param("pageSize", "10")
                        .param("sourceFileName", "login-filter-" + suffix)
                        .param("login", "有失败")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(failedBatchId));

        mockMvc.perform(get("/api/account-imports")
                        .param("page", "1")
                        .param("pageSize", "10")
                        .param("sourceFileName", "login-filter-" + suffix)
                        .param("login", "无失败")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(cleanBatchId));
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
                        .param("text", VALID_JSON_TEXT)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andReturn();

        String body = importResult.getResponse().getContentAsString();
        // 从响应中提取 data.id (batchId)
        Long batchId = objectMapper.readTree(body).path("data").path("id").longValue();
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
    // C4: GET /api/account-imports/{batchId}/export — 原始格式导出
    // -----------------------------------------------------------------------

    @Test
    void get_exportTextImport_returnsTxtAttachment() throws Exception {
        MvcResult importResult = mockMvc.perform(multipart("/api/account-imports")
                        .param("importFormat", "2")
                        .param("deviceOs", "1")
                        .param("accountType", "1")
                        .param("text", VALID_JSON_TEXT)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andReturn();

        Long batchId = objectMapper.readTree(importResult.getResponse().getContentAsString())
                .path("data").path("id").longValue();

        mockMvc.perform(get("/api/account-imports/{batchId}/export", batchId)
                        .param("scope", "all")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "account-import-" + batchId + "-all.txt")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("8613900000001")));
    }

    @Test
    void get_exportZipImport_returnsZipAttachment() throws Exception {
        String json = "{\"wid\":\"8613900000099\",\"registrationId\":99,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "accounts.zip",
                "application/zip",
                zip("8613900000099.json", json));

        MvcResult importResult = mockMvc.perform(multipart("/api/account-imports")
                        .file(file)
                        .param("importFormat", "2")
                        .param("deviceOs", "1")
                        .param("accountType", "1")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andReturn();

        Long batchId = objectMapper.readTree(importResult.getResponse().getContentAsString())
                .path("data").path("id").longValue();

        MvcResult exportResult = mockMvc.perform(get("/api/account-imports/{batchId}/export", batchId)
                        .param("scope", "all")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/zip"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "account-import-" + batchId + "-all.zip")))
                .andReturn();

        java.util.Map<String, String> entries = unzip(exportResult.getResponse().getContentAsByteArray());
        assertThat(entries).containsEntry("8613900000099.json", json);
    }

    private byte[] zip(String entryName, String content) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
            zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private Long importFileBatch(String filename, String wid) throws Exception {
        String json = "[{\"wid\":\"" + wid + "\","
                + "\"registrationId\":99,\"noiseKey\":{},"
                + "\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, "application/json", json.getBytes());

        MvcResult result = mockMvc.perform(multipart("/api/account-imports")
                        .file(file)
                        .param("importFormat", "2")
                        .param("deviceOs", "1")
                        .param("accountType", "1")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        Long batchId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").longValue();
        Assertions.assertTrue(batchId > 0);
        return batchId;
    }

    private void settleImportLoginResult(Long batchId, int loginResult) {
        int updated = jdbc.update("""
                UPDATE account_import_detail
                SET login_result = ?, login_settled_at = ?
                WHERE batch_id = ?
                """, loginResult, System.currentTimeMillis(), batchId);
        Assertions.assertEquals(1, updated);
    }

    private java.util.Map<String, String> unzip(byte[] bytes) throws Exception {
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(),
                        new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                zis.closeEntry();
            }
        }
        return entries;
    }
}
