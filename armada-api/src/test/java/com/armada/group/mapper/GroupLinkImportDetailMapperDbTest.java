package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.GroupLinkImportResult;
import com.armada.group.model.dto.GroupLinkImportDetailQuery;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkImportBatch;
import com.armada.group.model.entity.GroupLinkImportDetail;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.enums.GroupLinkImportFailReason;
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.security.DataScope;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * GroupLinkImportDetailMapper 真库测试:验批量插入/分页 JOIN/失败筛选。
 * 每个 @Test 在 @Transactional 内运行并回滚,数据互不干扰。
 */
class GroupLinkImportDetailMapperDbTest extends DbTestBase {

    @Autowired
    private GroupLinkImportDetailMapper detailMapper;

    @Autowired
    private GroupLinkImportBatchMapper batchMapper;

    @Autowired
    private GroupLinkLabelMapper labelMapper;

    @Autowired
    private GroupLinkMapper groupLinkMapper;

    // ---- 辅助方法 ----

    private GroupLinkLabel insertLabel(String name) {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setOwnerUserId(1L);
        label.setName(name);
        label.setRegion("印度");
        label.setRemark("测试");
        long now = System.currentTimeMillis();
        label.setCreatedAt(now);
        label.setUpdatedAt(now);
        labelMapper.insert(label);
        return label;
    }

    private GroupLinkImportBatch insertBatch(Long labelId, String fileName) {
        GroupLinkImportBatch batch = new GroupLinkImportBatch();
        batch.setOwnerUserId(1L);
        batch.setLabelId(labelId);
        batch.setBatchName("测试批次");
        batch.setSourceFileName(fileName);
        batch.setCreatedAt(System.currentTimeMillis());
        batchMapper.insert(batch);
        return batch;
    }

    private GroupLinkImportDetail buildDetail(Long batchId, int lineNo, String url, int result, String failReason) {
        GroupLinkImportDetail detail = new GroupLinkImportDetail();
        detail.setBatchId(batchId);
        detail.setLineNo(lineNo);
        detail.setRawUrl(url);
        detail.setGroupName("测试群");
        detail.setResult(result);
        detail.setFailReason(failReason);
        detail.setCreatedAt(System.currentTimeMillis());
        return detail;
    }

    private Long insertActiveLink(Long labelId, Long batchId, String url) {
        GroupLink link = new GroupLink();
        link.setOwnerUserId(1L);
        link.setLinkUrl(url);
        link.setLabelId(labelId);
        link.setImportBatchId(batchId);
        long now = System.currentTimeMillis();
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        groupLinkMapper.insert(link);
        return link.getId();
    }

    // ---- 测试 ----

    @Test
    void batchInsert_andSelectPage_joinsSourceFileName() {
        GroupLinkLabel label = insertLabel("明细分页测试分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "detail_test.xlsx");

        List<GroupLinkImportDetail> details = List.of(
                buildDetail(batch.getId(), 1, "chat.whatsapp.com/Detail1",
                        GroupLinkImportResult.SUCCESS.code(), null),
                buildDetail(batch.getId(), 2, "chat.whatsapp.com/Detail2",
                        GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.DUPLICATE),
                buildDetail(batch.getId(), 3, "chat.whatsapp.com/Detail3",
                        GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.FORMAT_ERROR)
        );
        int inserted = detailMapper.batchInsert(details);
        assertThat(inserted).isEqualTo(3);

        GroupLinkImportDetailQuery query = new GroupLinkImportDetailQuery();
        query.applyDataScope(DataScope.all(1L));
        query.setBatchId(batch.getId());
        query.setPage(1);
        query.setPageSize(10);

        long total = detailMapper.countByQuery(query);
        assertThat(total).isEqualTo(3);

        List<GroupLinkImportDetailVoRow> rows = detailMapper.selectPage(query);
        assertThat(rows).hasSize(3);
        // JOIN 出 sourceFileName
        rows.forEach(r -> assertThat(r.getSourceFileName()).isEqualTo("detail_test.xlsx"));
        // 按行号升序
        assertThat(rows.get(0).getLineNo()).isEqualTo(1);
        assertThat(rows.get(2).getLineNo()).isEqualTo(3);
    }

    @Test
    void selectFailed_returnsOnlyFailedResult() {
        GroupLinkLabel label = insertLabel("失败导出测试分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "failed_test.txt");

        List<GroupLinkImportDetail> details = List.of(
                buildDetail(batch.getId(), 1, "chat.whatsapp.com/Ok1",
                        GroupLinkImportResult.SUCCESS.code(), null),
                buildDetail(batch.getId(), 2, "chat.whatsapp.com/Dup1",
                        GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.DUPLICATE),
                buildDetail(batch.getId(), 3, "bad-url",
                        GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.FORMAT_ERROR)
        );
        detailMapper.batchInsert(details);

        List<GroupLinkImportDetailVoRow> failed = detailMapper.selectFailed(
                null, batch.getId(), DataScope.all(1L));
        assertThat(failed).hasSize(2);
        failed.forEach(r -> assertThat(r.getResult()).isEqualTo(GroupLinkImportResult.FAILED.code()));
    }

    @Test
    void selectPage_filtersFailedRowsByFailReason() {
        GroupLinkLabel label = insertLabel("失败原因筛选测试分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "fail_reason_test.txt");

        detailMapper.batchInsert(List.of(
                buildDetail(batch.getId(), 1, "chat.whatsapp.com/Ok",
                        GroupLinkImportResult.SUCCESS.code(), null),
                buildDetail(batch.getId(), 2, "chat.whatsapp.com/Dup",
                        GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.DUPLICATE),
                buildDetail(batch.getId(), 3, "bad-url",
                        GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.FORMAT_ERROR)
        ));

        GroupLinkImportDetailQuery query = new GroupLinkImportDetailQuery();
        query.applyDataScope(DataScope.all(1L));
        query.setBatchId(batch.getId());
        query.setResult(GroupLinkImportResult.FAILED.code());
        query.setFailReason(GroupLinkImportFailReason.DUPLICATE);
        query.setPage(1);
        query.setPageSize(10);

        assertThat(detailMapper.countByQuery(query)).isEqualTo(1);
        List<GroupLinkImportDetailVoRow> rows = detailMapper.selectPage(query);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRawUrl()).isEqualTo("chat.whatsapp.com/Dup");
        assertThat(rows.get(0).getFailReason()).isEqualTo(GroupLinkImportFailReason.DUPLICATE);
    }

    @Test
    void selectPage_acceptsLegacyDuplicateResultCode() {
        GroupLinkLabel label = insertLabel("旧四态兼容测试分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "legacy_result_test.txt");

        detailMapper.batchInsert(List.of(
                buildDetail(batch.getId(), 1, "chat.whatsapp.com/Dup",
                        GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.DUPLICATE),
                buildDetail(batch.getId(), 2, "bad-url",
                        GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.FORMAT_ERROR)
        ));

        GroupLinkImportDetailQuery query = new GroupLinkImportDetailQuery();
        query.applyDataScope(DataScope.all(1L));
        query.setBatchId(batch.getId());
        query.setResult(3);
        query.setPage(1);
        query.setPageSize(10);

        assertThat(query.getResult()).isEqualTo(GroupLinkImportResult.FAILED.code());
        assertThat(query.getFailReason()).isEqualTo(GroupLinkImportFailReason.DUPLICATE);
        assertThat(detailMapper.countByQuery(query)).isEqualTo(1);
        List<GroupLinkImportDetailVoRow> rows = detailMapper.selectPage(query);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRawUrl()).isEqualTo("chat.whatsapp.com/Dup");
    }

    @Test
    void selectPage_byLabelId_acrossBatches() {
        GroupLinkLabel label = insertLabel("跨批次查询测试分组");
        GroupLinkImportBatch batch1 = insertBatch(label.getId(), "batch1.txt");
        GroupLinkImportBatch batch2 = insertBatch(label.getId(), "batch2.txt");

        detailMapper.batchInsert(List.of(
                buildDetail(batch1.getId(), 1, "chat.whatsapp.com/B1L1",
                        GroupLinkImportResult.SUCCESS.code(), null)
        ));
        detailMapper.batchInsert(List.of(
                buildDetail(batch2.getId(), 1, "chat.whatsapp.com/B2L1",
                        GroupLinkImportResult.SUCCESS.code(), null)
        ));

        GroupLinkImportDetailQuery query = new GroupLinkImportDetailQuery();
        query.applyDataScope(DataScope.all(1L));
        query.setLabelId(label.getId());
        query.setPage(1);
        query.setPageSize(10);

        assertThat(detailMapper.countByQuery(query)).isEqualTo(2);
        assertThat(detailMapper.selectPage(query)).hasSize(2);
    }

    @Test
    void selectPage_byLabelIdUsesCurrentLinkOwnershipForSuccessRowsAndOriginalBatchForFailures() {
        GroupLinkLabel sourceLabel = insertLabel("明细当前归属-源分组");
        GroupLinkLabel targetLabel = insertLabel("明细当前归属-目标分组");
        GroupLinkImportBatch sourceBatch = insertBatch(sourceLabel.getId(), "current_owner.txt");

        Long linkId = insertActiveLink(sourceLabel.getId(), sourceBatch.getId(), "chat.whatsapp.com/CurrentOwnerOk");
        GroupLinkImportDetail success = buildDetail(sourceBatch.getId(), 1, "chat.whatsapp.com/CurrentOwnerOk",
                GroupLinkImportResult.SUCCESS.code(), null);
        success.setGroupLinkId(linkId);
        GroupLinkImportDetail failed = buildDetail(sourceBatch.getId(), 2, "bad-current-owner-url",
                GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.FORMAT_ERROR);
        detailMapper.batchInsert(List.of(success, failed));

        groupLinkMapper.migrateToLabel(
                List.of(linkId), targetLabel.getId(),
                com.armada.shared.security.DataScope.all(1L), System.currentTimeMillis());

        GroupLinkImportDetailQuery sourceQuery = new GroupLinkImportDetailQuery();
        sourceQuery.applyDataScope(DataScope.all(1L));
        sourceQuery.setLabelId(sourceLabel.getId());
        sourceQuery.setPage(1);
        sourceQuery.setPageSize(10);
        assertThat(detailMapper.countByQuery(sourceQuery)).isEqualTo(1);
        assertThat(detailMapper.selectPage(sourceQuery))
                .extracting(GroupLinkImportDetailVoRow::getRawUrl)
                .containsExactly("bad-current-owner-url");

        GroupLinkImportDetailQuery targetQuery = new GroupLinkImportDetailQuery();
        targetQuery.applyDataScope(DataScope.all(1L));
        targetQuery.setLabelId(targetLabel.getId());
        targetQuery.setPage(1);
        targetQuery.setPageSize(10);
        assertThat(detailMapper.countByQuery(targetQuery)).isEqualTo(1);
        List<GroupLinkImportDetailVoRow> targetRows = detailMapper.selectPage(targetQuery);
        assertThat(targetRows).hasSize(1);
        assertThat(targetRows.get(0).getRawUrl()).isEqualTo("chat.whatsapp.com/CurrentOwnerOk");
        assertThat(targetRows.get(0).getSourceFileName()).isEqualTo("current_owner.txt");
    }

    @Test
    void selectFailed_byLabelId_returnsFailedRows() {
        // I-5:验 selectFailed(labelId路径,batchId=null) 能查到失败行
        GroupLinkLabel label = insertLabel("labelId路径失败行测试分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "label_failed_test.txt");

        List<GroupLinkImportDetail> details = List.of(
                buildDetail(batch.getId(), 1, "chat.whatsapp.com/LF1",
                        GroupLinkImportResult.SUCCESS.code(), null),
                buildDetail(batch.getId(), 2, "chat.whatsapp.com/LF2",
                        GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.DUPLICATE),
                buildDetail(batch.getId(), 3, "chat.whatsapp.com/LF3",
                        GroupLinkImportResult.FAILED.code(), GroupLinkImportFailReason.FORMAT_ERROR)
        );
        detailMapper.batchInsert(details);

        List<GroupLinkImportDetailVoRow> failed = detailMapper.selectFailed(
                label.getId(), null, DataScope.all(1L));
        assertThat(failed).hasSize(2);
        failed.forEach(r -> assertThat(r.getResult()).isEqualTo(GroupLinkImportResult.FAILED.code()));
    }

    @Test
    void selectFailed_isolatesByTenant() {
        // I-5 + C-2:跨租户隔离 — 租户2插入的失败行对租户1不可见
        // 先切到租户2,插入一条失败行
        TenantContext.set(2L);
        GroupLinkLabel label2 = insertLabel("租户2失败行测试分组");
        GroupLinkImportBatch batch2 = insertBatch(label2.getId(), "tenant2_failed.txt");
        detailMapper.batchInsert(List.of(
                buildDetail(batch2.getId(), 1, "chat.whatsapp.com/T2F1",
                        GroupLinkImportResult.FAILED.code(), "租户2批内重复")
        ));

        // 切回租户1,用最弱条件(labelId=null, batchId=null)查询,断言查不到租户2的数据
        TenantContext.set(1L);
        List<GroupLinkImportDetailVoRow> rows = detailMapper.selectFailed(
                null, null, DataScope.all(1L));
        boolean containsTenant2Row = rows.stream()
                .anyMatch(r -> "chat.whatsapp.com/T2F1".equals(r.getRawUrl()));
        assertThat(containsTenant2Row).isFalse();

        // 测试方法末尾重置回租户1(防串),@AfterEach 也会 clear
        TenantContext.set(1L);
    }
}
