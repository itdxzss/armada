package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.dto.AccountImportDetailQuery;
import com.armada.account.model.dto.AccountImportQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.entity.AccountImportBatch;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.vo.AccountImportBatchVoRow;
import com.armada.account.model.vo.AccountImportDetailVoRow;
import com.armada.account.service.AccountImportService;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.vo.AccountImportDetailVO;
import com.armada.shared.response.PageResult;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 批次列表 + 明细列表 + 导出 CSV 真库测试。
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

    private Long createBatch(Long groupId, String batchName, int total,
                              int imported, int duplicate, int formatError, long now) {
        AccountImportBatch b = new AccountImportBatch();
        b.setAccountGroupId(groupId);
        b.setBatchName(batchName);
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

        // 找到我们插入的批次(按批次名)
        AccountImportBatchVoRow found = page.stream()
                .filter(r -> "批次A".equals(r.getBatchName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到批次A"));
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
        boolean hasGroupA = page.stream().anyMatch(r -> "批次-groupA".equals(r.getBatchName()));
        boolean hasGroupB = page.stream().anyMatch(r -> "批次-groupB".equals(r.getBatchName()));
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

    /**
     * selectAllByBatch scope=all:返回全部 4 条(不分页,导出用)。
     */
    @Test
    void selectAllByBatch_scopeAll() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-export-all", now);
        Long batchId = createBatch(groupId, "批次-export", 4, 1, 1, 2, now);
        insertDetails(batchId, now);

        List<AccountImportDetailVoRow> all = detailMapper.selectAllByBatch(batchId, "all");
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
     * AccountImportService.exportDetailsCsv:5 列表头 + 数据行数对 + 含 BOM。
     */
    @Test
    void service_exportDetailsCsv_headerAndRowCount() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-csv-export", now);
        Long batchId = createBatch(groupId, "批次-csv", 4, 1, 1, 2, now);
        insertDetails(batchId, now);

        String csv = importService.exportDetailsCsv(batchId, "all");

        // BOM 开头
        assertThat(csv).startsWith("﻿");

        String[] lines = csv.split("\n");
        // 表头 + 4 数据行
        assertThat(lines.length).isEqualTo(5);
        assertThat(lines[0]).isEqualTo("﻿账号,状态,失败原因,分组,创建时间");

        // 第 1 条数据行(成功入库行)
        assertThat(lines[1]).contains("8613900001111");
        assertThat(lines[1]).contains("成功入库");
        assertThat(lines[1]).contains("分组-csv-export");
    }

    /**
     * exportDetailsCsv scope=fail:只有 3 行数据(BOM+表头+3行)。
     */
    @Test
    void service_exportDetailsCsv_scopeFail_threeRows() {
        long now = System.currentTimeMillis();
        Long groupId = createGroup("分组-csv-fail", now);
        Long batchId = createBatch(groupId, "批次-csv-fail", 4, 1, 1, 2, now);
        insertDetails(batchId, now);

        String csv = importService.exportDetailsCsv(batchId, "fail");
        String[] lines = csv.split("\n");
        // 表头 + 3 失败行
        assertThat(lines.length).isEqualTo(4);
    }
}
