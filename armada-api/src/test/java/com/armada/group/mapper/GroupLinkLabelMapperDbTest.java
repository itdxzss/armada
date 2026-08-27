package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.group.model.dto.GroupLinkLabelQuery;
import com.armada.group.model.entity.GroupLinkImportBatch;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupLinkLabelVoRow;
import com.armada.shared.security.DataScope;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

/**
 * GroupLinkLabelMapper 真库测试:验分页/唯一/聚合/复活流程。
 * 每个 @Test 在 @Transactional 内运行并回滚,数据互不干扰。
 */
class GroupLinkLabelMapperDbTest extends DbTestBase {

    @Autowired
    private GroupLinkLabelMapper mapper;

    @Autowired
    private GroupLinkMapper groupLinkMapper;

    @Autowired
    private GroupLinkImportBatchMapper importBatchMapper;

    // ---- 辅助方法 ----

    private GroupLinkLabel buildLabel(String name) {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setOwnerUserId(1L);
        label.setName(name);
        label.setRegion("印度");
        label.setRemark("测试");
        long now = System.currentTimeMillis();
        label.setCreatedAt(now);
        label.setUpdatedAt(now);
        return label;
    }

    // ---- 测试 ----

    @Test
    void insert_then_selectActiveByName() {
        GroupLinkLabel label = buildLabel("测试分组A");
        mapper.insert(label);
        assertThat(label.getId()).isNotNull();

        GroupLinkLabel found = mapper.selectActiveByName("测试分组A", 1L);
        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("测试分组A");
        assertThat(found.getRegion()).isEqualTo("印度");
        assertThat(found.getDeletedAt()).isNull();
    }

    @Test
    void uniqueNameRejectsDuplicateActive() {
        mapper.insert(buildLabel("重名分组"));
        assertThatThrownBy(() -> mapper.insert(buildLabel("重名分组")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void selectPage_linkCount_countsActiveLinksOnly() {
        // 插分组
        GroupLinkLabel label = buildLabel("聚合测试分组");
        mapper.insert(label);
        Long labelId = label.getId();

        // 插 2 条活跃 + 1 条软删 group_link,验 linkCount=2(聚合只数活跃)
        insertActiveLink("chat.whatsapp.com/Count1", labelId);
        insertActiveLink("chat.whatsapp.com/Count2", labelId);
        Long softDeletedId = insertActiveLink("chat.whatsapp.com/CountDeleted", labelId);
        groupLinkMapper.softDeleteByIds(
                java.util.List.of(softDeletedId),
                com.armada.shared.security.DataScope.all(1L), System.currentTimeMillis());

        GroupLinkLabelQuery query = new GroupLinkLabelQuery();
        query.applyDataScope(DataScope.all(1L));
        query.setId(labelId);
        query.setPage(1);
        query.setPageSize(10);

        long total = mapper.countPage(query);
        assertThat(total).isEqualTo(1);

        List<GroupLinkLabelVoRow> rows = mapper.selectPage(query);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getName()).isEqualTo("聚合测试分组");
        // 验证聚合只数活跃链接(2条),软删的不计
        assertThat(rows.get(0).getLinkCount()).isEqualTo(2L);
    }

    @Test
    void selectPage_includesImportStatsByLabel() {
        GroupLinkLabel label = buildLabel("导入统计分组");
        mapper.insert(label);

        insertImportBatch(label.getId(), "第一批", "a.txt", 3, 2, 0, 1);
        insertImportBatch(label.getId(), "第二批", "b.txt", 4, 1, 1, 2);
        insertActiveLink("chat.whatsapp.com/Stats1", label.getId());
        insertActiveLink("chat.whatsapp.com/Stats2", label.getId());
        insertActiveLink("chat.whatsapp.com/Stats3", label.getId());
        insertActiveLink("chat.whatsapp.com/Stats4", label.getId());

        GroupLinkLabelQuery query = new GroupLinkLabelQuery();
        query.applyDataScope(DataScope.all(1L));
        query.setId(label.getId());
        query.setPage(1);
        query.setPageSize(10);

        List<GroupLinkLabelVoRow> rows = mapper.selectPage(query);

        assertThat(rows).hasSize(1);
        GroupLinkLabelVoRow row = rows.get(0);
        assertThat(row.getFileCount()).isEqualTo(2L);
        assertThat(row.getTotalRows()).isEqualTo(7L);
        assertThat(row.getSuccessRows()).isEqualTo(4L);
        assertThat(row.getFailedRows()).isEqualTo(3L);
        assertThat(row.getLatestSourceFile()).isEqualTo("b.txt");
        assertThat(row.getLatestImportedAt()).isNotNull();
        assertThat(row.getStatus()).isEqualTo("PARTIAL");
    }

    @Test
    void selectPage_importStatsFollowCurrentLinkOwnershipAfterMigration() {
        GroupLinkLabel source = buildLabel("统计当前归属-源分组");
        GroupLinkLabel target = buildLabel("统计当前归属-目标分组");
        mapper.insert(source);
        mapper.insert(target);

        insertImportBatch(source.getId(), "来源批次", "moved.txt", 2, 1, 0, 1);
        Long linkId = insertActiveLink("chat.whatsapp.com/MovedStats", source.getId());
        groupLinkMapper.migrateToLabel(
                List.of(linkId), target.getId(),
                com.armada.shared.security.DataScope.all(1L), System.currentTimeMillis());

        GroupLinkLabelQuery sourceQuery = new GroupLinkLabelQuery();
        sourceQuery.applyDataScope(DataScope.all(1L));
        sourceQuery.setId(source.getId());
        sourceQuery.setPage(1);
        sourceQuery.setPageSize(10);
        GroupLinkLabelVoRow sourceRow = mapper.selectPage(sourceQuery).get(0);
        assertThat(sourceRow.getLinkCount()).isEqualTo(0L);
        assertThat(sourceRow.getSuccessRows()).isEqualTo(0L);
        assertThat(sourceRow.getFailedRows()).isEqualTo(1L);
        assertThat(sourceRow.getTotalRows()).isEqualTo(1L);
        assertThat(sourceRow.getStatus()).isEqualTo("FAILED");

        GroupLinkLabelQuery targetQuery = new GroupLinkLabelQuery();
        targetQuery.applyDataScope(DataScope.all(1L));
        targetQuery.setId(target.getId());
        targetQuery.setPage(1);
        targetQuery.setPageSize(10);
        GroupLinkLabelVoRow targetRow = mapper.selectPage(targetQuery).get(0);
        assertThat(targetRow.getLinkCount()).isEqualTo(1L);
        assertThat(targetRow.getSuccessRows()).isEqualTo(1L);
        assertThat(targetRow.getFailedRows()).isEqualTo(0L);
        assertThat(targetRow.getTotalRows()).isEqualTo(1L);
        assertThat(targetRow.getStatus()).isEqualTo("DONE");
    }

    @Test
    void selectPage_filtersByImportTimeAndStatus() {
        long base = 1_785_225_600_000L;
        GroupLinkLabel empty = buildLabel("导入筛选-空");
        GroupLinkLabel done = buildLabel("导入筛选-完成");
        GroupLinkLabel partial = buildLabel("导入筛选-部分");
        GroupLinkLabel failed = buildLabel("导入筛选-失败");
        GroupLinkLabel outsideRange = buildLabel("导入筛选-范围外");
        mapper.insert(empty);
        mapper.insert(done);
        mapper.insert(partial);
        mapper.insert(failed);
        mapper.insert(outsideRange);

        insertImportBatch(done.getId(), "完成批次", "done.txt", 2, 2, 0, 0, base + 1_000);
        insertImportBatch(partial.getId(), "部分批次", "partial.txt", 3, 1, 0, 2, base + 1_000);
        insertImportBatch(failed.getId(), "失败批次", "failed.txt", 2, 0, 0, 2, base + 1_000);
        insertImportBatch(outsideRange.getId(), "范围外批次", "outside.txt", 3, 1, 0, 2, base + 100_000);
        insertActiveLink("chat.whatsapp.com/Done1", done.getId());
        insertActiveLink("chat.whatsapp.com/Done2", done.getId());
        insertActiveLink("chat.whatsapp.com/Partial1", partial.getId());
        insertActiveLink("chat.whatsapp.com/OutsideRange1", outsideRange.getId());

        GroupLinkLabelQuery query = new GroupLinkLabelQuery();
        query.applyDataScope(DataScope.all(1L));
        query.setKeyword("导入筛选");
        query.setStatus("PARTIAL");
        query.setImportedFrom(base);
        query.setImportedTo(base + 2_000);
        query.setPage(1);
        query.setPageSize(10);

        List<GroupLinkLabelVoRow> partialRows = mapper.selectPage(query);
        assertThat(partialRows).extracting(GroupLinkLabelVoRow::getName)
                .containsExactly("导入筛选-部分");

        query.setStatus("EMPTY");
        query.setImportedFrom(null);
        query.setImportedTo(null);
        assertThat(mapper.selectPage(query)).extracting(GroupLinkLabelVoRow::getName)
                .containsExactly("导入筛选-空");

        query.setStatus("DONE");
        assertThat(mapper.selectPage(query)).extracting(GroupLinkLabelVoRow::getName)
                .containsExactly("导入筛选-完成");

        query.setStatus("FAILED");
        assertThat(mapper.selectPage(query)).extracting(GroupLinkLabelVoRow::getName)
                .containsExactly("导入筛选-失败");
    }

    /** 插入一条活跃 group_link 并返回 id。 */
    private Long insertActiveLink(String url, Long labelId) {
        com.armada.group.model.entity.GroupLink link = new com.armada.group.model.entity.GroupLink();
        link.setOwnerUserId(1L);
        link.setLinkUrl(url);
        link.setLabelId(labelId);
        long now = System.currentTimeMillis();
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        groupLinkMapper.insert(link);
        return link.getId();
    }

    private void insertImportBatch(Long labelId, String batchName, String sourceFileName,
                                   int totalRows, int insertedRows, int adoptedRows, int failedRows) {
        insertImportBatch(labelId, batchName, sourceFileName,
                totalRows, insertedRows, adoptedRows, failedRows, System.currentTimeMillis());
    }

    private void insertImportBatch(Long labelId, String batchName, String sourceFileName,
                                   int totalRows, int insertedRows, int adoptedRows, int failedRows,
                                   long createdAt) {
        GroupLinkImportBatch batch = new GroupLinkImportBatch();
        batch.setOwnerUserId(1L);
        batch.setLabelId(labelId);
        batch.setBatchName(batchName);
        batch.setSourceFileName(sourceFileName);
        batch.setTotalRows(totalRows);
        batch.setInsertedRows(insertedRows);
        batch.setAdoptedRows(adoptedRows);
        batch.setFailedRows(failedRows);
        batch.setCreatedAt(createdAt);
        importBatchMapper.insert(batch);
    }

    @Test
    void softDeleteThenReviveByName() {
        // 插入 -> 软删 -> selectDeletedByName 命中 -> reviveById -> selectActiveByName 命中
        GroupLinkLabel label = buildLabel("复活测试分组");
        mapper.insert(label);
        Long id = label.getId();

        // 软删
        mapper.softDeleteByIds(List.of(id), DataScope.all(1L), System.currentTimeMillis());
        assertThat(mapper.selectActiveByName("复活测试分组", 1L)).isNull();

        // 查软删
        GroupLinkLabel deleted = mapper.selectDeletedByName("复活测试分组", 1L);
        assertThat(deleted).isNotNull();
        assertThat(deleted.getDeletedAt()).isNotNull();

        // 复活
        mapper.reviveById(id, 1L, System.currentTimeMillis());

        // 重新活跃
        GroupLinkLabel revived = mapper.selectActiveByName("复活测试分组", 1L);
        assertThat(revived).isNotNull();
        assertThat(revived.getDeletedAt()).isNull();
    }

    @Test
    void selectById_returnsNullAfterSoftDelete() {
        GroupLinkLabel label = buildLabel("按ID查测试");
        mapper.insert(label);
        Long id = label.getId();

        assertThat(mapper.selectById(id, DataScope.all(1L))).isNotNull();
        mapper.softDeleteByIds(List.of(id), DataScope.all(1L), System.currentTimeMillis());
        assertThat(mapper.selectById(id, DataScope.all(1L))).isNull();
    }

    @Test
    void updateProfile_changesNameAndRegion() {
        GroupLinkLabel label = buildLabel("修改前名称");
        mapper.insert(label);

        GroupLinkLabel update = new GroupLinkLabel();
        update.setId(label.getId());
        update.setName("修改后名称");
        update.setRegion("巴基斯坦");
        update.setRemark("新备注");
        update.setUpdatedAt(System.currentTimeMillis());
        mapper.updateProfile(update, DataScope.all(1L));

        GroupLinkLabel found = mapper.selectById(label.getId(), DataScope.all(1L));
        assertThat(found.getName()).isEqualTo("修改后名称");
        assertThat(found.getRegion()).isEqualTo("巴基斯坦");
    }
}
