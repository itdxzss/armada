package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.model.dto.AccountImportDetailQuery;
import com.armada.account.model.dto.AccountImportQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.entity.AccountImportBatch;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.vo.AccountImportBatchVoRow;
import com.armada.account.model.vo.AccountImportDetailVoRow;
import com.armada.account.model.vo.AccountImportExportFile;
import com.armada.account.model.vo.AccountImportExportRow;
import com.armada.account.service.AccountImportService;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.vo.AccountImportDetailVO;
import com.armada.shared.response.PageResult;
import com.armada.testsupport.DbTestBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 批次列表 + 明细列表 + 导出真库测试。
 *
 * <p>每个 @Test 在 @Transactional 内执行并回滚,数据互不干扰。
 * TDD 路径:先写测试(RED),再实现 Mapper/Service,再绿。</p>
 */
class AccountImportListMapperDbTest extends DbTestBase {

    @Autowired
    AccountImportBatchMapper batchMapper;

    @Autowired
    AccountImportDetailMapper detailMapper;

    @Autowired
    AccountGroupMapper groupMapper;

    @Autowired
    AccountImportService importService;

    // ---- 辅助方法 ----

    private Long createGroup(String name, long now) {
        AccountGroup g = new AccountGroup();
        g.setName(name);
        g.setSystemBuiltin(0);
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        groupMapper.insert(g);
        return g.getId();
    }

    private Long createBatch(Long groupId, String sourceFileName, int total,
                              int imported, int duplicate, int formatError, long now) {
        AccountImportBatch b = new AccountImportBatch();
        b.setAccountGroupId(groupId);
        b.setSourceFileName(sourceFileName);
        b.setImportFormat(2);
        b.setDeviceOs(1);
        b.setAccountType(2);
        b.setTotalRows(total);
        b.setImportedRows(imported);
        b.setDuplicateRows(duplicate);
        b.setFormatErrorRows(formatError);
        b.setStatus(2);
        b.setCreatedAt(now);
        batchMapper.insert(b);
        return b.getId();
    }

    private Long createBatch(Long groupId, String sourceFileName, String sourceFileType,
                             int total, int imported, int duplicate, int formatError, long now) {
        AccountImportBatch b = new AccountImportBatch();
        b.setAccountGroupId(groupId);
        b.setSourceFileName(sourceFileName);
        b.setSourceFileType(sourceFileType);
        b.setImportFormat(2);
        b.setDeviceOs(1);
        b.setAccountType(2);
        b.setTotalRows(total);
        b.setImportedRows(imported);
        b.setDuplicateRows(duplicate);
        b.setFormatErrorRows(formatError);
        b.setStatus(2);
        b.setCreatedAt(now);
        batchMapper.insert(b);
        return b.getId();
    }

    private void insertDetails(Long batchId, long now) {
        // 1 条成功 + 1 条重复 + 1 条格式错误 + 1 条凭据不全
        AccountImportDetail d1 = new AccountImportDetail();
        d1.setBatchId(batchId);
        d1.setLineNo(1);
        d1.setWsPhone("8613900001111");
        d1.setParseResult(1);   // 成功入库
        d1.setCreatedAt(now);

        AccountImportDetail d2 = new AccountImportDetail();
        d2.setBatchId(batchId);
        d2.setLineNo(2);
        d2.setWsPhone("8613900002222");
        d2.setParseResult(2);   // 重复
        d2.setFailReason("批内重复");
        d2.setCreatedAt(now);

        AccountImportDetail d3 = new AccountImportDetail();
        d3.setBatchId(batchId);
        d3.setLineNo(3);
        d3.setWsPhone("bad-phone");
        d3.setParseResult(3);   // 格式错误
        d3.setFailReason("格式不合法");
        d3.setCreatedAt(now);

        AccountImportDetail d4 = new AccountImportDetail();
        d4.setBatchId(batchId);
        d4.setLineNo(4);
        d4.setWsPhone("8613900004444");
        d4.setParseResult(4);   // 凭据不全
        d4.setFailReason("缺 registrationId");
        d4.setCreatedAt(now);

        detailMapper.batchInsert(List.of(d1, d2, d3, d4));
    }

    private AccountImportDetail detail(Long batchId,
                                       int lineNo,
                                       String wsPhone,
                                       int parseResult,
                                       String failReason,
                                       long createdAt) {
        AccountImportDetail detail = new AccountImportDetail();
        detail.setBatchId(batchId);
        detail.setLineNo(lineNo);
        detail.setWsPhone(wsPhone);
        detail.setParseResult(parseResult);
        detail.setFailReason(failReason);
        detail.setCreatedAt(createdAt);
        return detail;
    }

    private void insertDetailsWithPayloads(Long batchId, long now) {
        List<AccountImportDetail> rows = new ArrayList<>();
        rows.add(detailWithPayload(batchId, 1, "861399100001", 1, null, "raw-success", "line-1.json", now));
        rows.add(detailWithPayload(batchId, 2, "861399200001", 2, "批内重复", "raw-duplicate", "line-2.json", now));
        rows.add(detailWithPayload(batchId, 3, "bad-phone-export", 3, "格式不合法", "raw-format", "line-3.json", now));
        rows.add(detailWithPayload(batchId, 4, "861399200003", 4, "缺 registrationId", "raw-incomplete", "line-4.json", now));
        detailMapper.batchInsert(rows);
    }

    private AccountImportDetail detailWithPayload(Long batchId, int lineNo, String phone, int result,
                                                  String reason, String rawPayload, String sourceEntryName, long now) {
        AccountImportDetail d = detail(batchId, lineNo, phone, result, reason, now);
        d.setRawPayload(rawPayload);
        d.setSourceEntryName(sourceEntryName);
        return d;
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

    @Test
    void mapper_persistsOriginalExportMetadata() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-original-export-meta", now);
        Long batchId = createBatch(groupId, "accounts.zip", "ZIP", 1, 1, 0, 0, now);

        AccountImportDetail detail = detail(batchId, 1, "861399900001", 1, null, now);
        detail.setRawPayload("{\"wid\":\"861399900001\",\"registrationId\":1}");
        detail.setSourceEntryName("861399900001.json");
        detailMapper.batchInsert(List.of(detail));

        AccountImportBatch savedBatch = batchMapper.selectById(batchId);
        assertThat(savedBatch.getSourceFileType()).isEqualTo("ZIP");

        List<AccountImportExportRow> rows = detailMapper.selectExportRowsByBatch(batchId, "all");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRawPayload()).contains("\"861399900001\"");
        assertThat(rows.get(0).getSourceEntryName()).isEqualTo("861399900001.json");
    }

    // ---- 批次列表测试 ----

    /**
     * 插入 1 个分组 + 1 个批次,countPage 应返回 1,selectPage 第 1 页应包含该批次且 groupName 正确。
     */
    @Test
    void listBatches_countAndGroupName() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("测试分组-list", now);
        createBatch(groupId, "批次A", 4, 1, 1, 2, now);

        AccountImportQuery q = new AccountImportQuery();
        // 使用默认分页(page=1, pageSize=10)

        long total = batchMapper.countPage(q);
        assertThat(total).isGreaterThanOrEqualTo(1);

        List<AccountImportBatchVoRow> page = batchMapper.selectPage(q);
        assertThat(page).isNotEmpty();

        // 找到我们插入的批次(按 source_file_name)
        AccountImportBatchVoRow found = page.stream()
                .filter(r -> "批次A".equals(r.getSourceFileName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到批次A(sourceFileName=批次A)"));
        assertThat(found.getGroupName()).isEqualTo("测试分组-list");
        assertThat(found.getTotalRows()).isEqualTo(4);
        assertThat(found.getImportedRows()).isEqualTo(1);
        assertThat(found.getDuplicateRows()).isEqualTo(1);
        assertThat(found.getFormatErrorRows()).isEqualTo(2);
        assertThat(found.getStatus()).isEqualTo(2);
    }

    /**
     * 按 accountGroupId 筛选:只有匹配分组的批次出现在结果中。
     */
    @Test
    void listBatches_filterByGroupId() {
        long now = System.currentTimeMillis();
        Long groupA = createGroup("分组A-" + now, now);
        Long groupB = createGroup("分组B-" + now, now);
        createBatch(groupA, "批次-groupA", 1, 1, 0, 0, now);
        createBatch(groupB, "批次-groupB", 1, 0, 1, 0, now);

        AccountImportQuery q = new AccountImportQuery();
        q.setAccountGroupId(groupA);

        long total = batchMapper.countPage(q);
        assertThat(total).isGreaterThanOrEqualTo(1);

        List<AccountImportBatchVoRow> page = batchMapper.selectPage(q);
        boolean hasGroupA = page.stream().anyMatch(r -> "批次-groupA".equals(r.getSourceFileName()));
        boolean hasGroupB = page.stream().anyMatch(r -> "批次-groupB".equals(r.getSourceFileName()));
        assertThat(hasGroupA).isTrue();
        assertThat(hasGroupB).isFalse();
    }

    // ---- 明细列表测试 ----

    /**
     * filter=all:countByBatch=4,selectPageByBatch 第 1 页返回全部 4 条。
     */
    @Test
    void listDetails_filterAll() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-detail-all", now);
        Long batchId = createBatch(groupId, "批次-detail", 4, 1, 1, 2, now);
        insertDetails(batchId, now);

        AccountImportDetailQuery q = new AccountImportDetailQuery();
        q.setBatchId(batchId);
        q.setFilter("all");
        q.setPageSize(20);

        long total = detailMapper.countByBatch(q);
        assertThat(total).isEqualTo(4);

        List<AccountImportDetailVoRow> page = detailMapper.selectPageByBatch(q);
        assertThat(page).hasSize(4);
        // 按行号升序
        assertThat(page.get(0).getLineNo()).isEqualTo(1);
        assertThat(page.get(3).getLineNo()).isEqualTo(4);
    }

    /**
     * filter=success:只返回 parse_result=1 的 1 条。
     */
    @Test
    void listDetails_filterSuccess() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-detail-success", now);
        Long batchId = createBatch(groupId, "批次-success", 4, 1, 1, 2, now);
        insertDetails(batchId, now);

        AccountImportDetailQuery q = new AccountImportDetailQuery();
        q.setBatchId(batchId);
        q.setFilter("success");
        q.setPageSize(20);

        long total = detailMapper.countByBatch(q);
        assertThat(total).isEqualTo(1);

        List<AccountImportDetailVoRow> page = detailMapper.selectPageByBatch(q);
        assertThat(page).hasSize(1);
        assertThat(page.get(0).getParseResult()).isEqualTo(1);
    }

    /**
     * filter=fail:只返回 parse_result IN (2,3,4) 的 3 条。
     */
    @Test
    void listDetails_filterFail_onlyReturnsFailCodes() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-detail-fail", now);
        Long batchId = createBatch(groupId, "批次-fail", 4, 1, 1, 2, now);
        insertDetails(batchId, now);

        AccountImportDetailQuery q = new AccountImportDetailQuery();
        q.setBatchId(batchId);
        q.setFilter("fail");
        q.setPageSize(20);

        long total = detailMapper.countByBatch(q);
        assertThat(total).isEqualTo(3);

        List<AccountImportDetailVoRow> page = detailMapper.selectPageByBatch(q);
        assertThat(page).hasSize(3);
        // parse_result 只含 2/3/4
        page.forEach(r -> assertThat(r.getParseResult()).isIn(2, 3, 4));
    }

    @Test
    void selectExportRowsByBatch_scopeAll() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-export-all", now);
        Long batchId = createBatch(groupId, "批次-export", "TXT", 4, 1, 1, 2, now);
        insertDetailsWithPayloads(batchId, now);

        List<AccountImportExportRow> all = detailMapper.selectExportRowsByBatch(batchId, "all");
        assertThat(all).hasSize(4);
    }

    // ---- Service 层测试 ----

    /**
     * AccountImportService.listDetails:filter=fail 只返 2/3/4;parseResultLabel 正确填充。
     */
    @Test
    void service_listDetails_filterFail_labelFilled() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-svc-fail", now);
        Long batchId = createBatch(groupId, "批次-svc-fail", 4, 1, 1, 2, now);
        insertDetails(batchId, now);

        AccountImportDetailQuery q = new AccountImportDetailQuery();
        q.setBatchId(batchId);
        q.setFilter("fail");
        q.setPageSize(20);

        PageResult<AccountImportDetailVO> result = importService.listDetails(q);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.list()).hasSize(3);
        result.list().forEach(vo -> {
            assertThat(vo.parseResult()).isIn(2, 3, 4);
            assertThat(vo.parseResultLabel()).isNotBlank();
        });
        // 验 label 具体值
        AccountImportDetailVO dup = result.list().stream()
                .filter(v -> v.parseResult() == 2).findFirst().orElseThrow();
        assertThat(dup.parseResultLabel()).isEqualTo("重复");
    }

    /**
     * AccountImportService.listDetails:分页明细返回所属分组名称,供前端明细表展示分组列。
     */
    @Test
    void service_listDetails_returnsGroupName() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-svc-detail-group", now);
        Long batchId = createBatch(groupId, "批次-svc-detail-group", 4, 1, 1, 2, now);
        insertDetails(batchId, now);

        AccountImportDetailQuery q = new AccountImportDetailQuery();
        q.setBatchId(batchId);
        q.setFilter("all");
        q.setPageSize(20);

        PageResult<AccountImportDetailVO> result = importService.listDetails(q);

        assertThat(result.list()).hasSize(4);
        assertThat(result.list()).extracting(AccountImportDetailVO::groupName)
                .containsOnly("分组-svc-detail-group");
    }

    @Test
    void service_exportDetails_txtScopeFail_exportsOriginalPayloadsOnly() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-txt-export-fail", now);
        Long batchId = createBatch(groupId, "accounts.txt", "TXT", 4, 1, 1, 2, now);
        insertDetailsWithPayloads(batchId, now);

        AccountImportExportFile file = importService.exportDetails(batchId, "fail");

        assertThat(file.filename()).isEqualTo("account-import-" + batchId + "-fail.txt");
        assertThat(file.contentType()).isEqualTo("text/plain;charset=UTF-8");
        String body = new String(file.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("raw-duplicate", "raw-format", "raw-incomplete");
        assertThat(body).doesNotContain("raw-success");
    }

    @Test
    void service_exportDetails_zipScopeSuccess_exportsOnlySuccessEntries() throws Exception {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-zip-export-success", now);
        Long batchId = createBatch(groupId, "accounts.zip", "ZIP", 4, 1, 1, 2, now);
        insertDetailsWithPayloads(batchId, now);

        AccountImportExportFile file = importService.exportDetails(batchId, "success");

        assertThat(file.filename()).isEqualTo("account-import-" + batchId + "-success.zip");
        assertThat(file.contentType()).isEqualTo("application/zip");
        java.util.Map<String, String> entries = unzip(file.bytes());
        assertThat(entries).containsOnlyKeys("line-1.json");
        assertThat(entries.get("line-1.json")).isEqualTo("raw-success");
    }

    @Test
    void service_exportDetails_missingOriginalPayload_throwsBusinessError() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-missing-payload", now);
        Long batchId = createBatch(groupId, "old.csv", null, 1, 1, 0, 0, now);
        detailMapper.batchInsert(List.of(detail(batchId, 1, "861399900009", 1, null, now)));

        assertThatThrownBy(() -> importService.exportDetails(batchId, "all"))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("该批次缺少原始导出材料");
    }
}
