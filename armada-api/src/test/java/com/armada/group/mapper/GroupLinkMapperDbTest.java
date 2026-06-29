package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkImportBatch;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.group.model.vo.GroupLinkVoRow;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import java.util.Locale;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GroupLinkMapper 真库测试:验唯一键/upsert 复活/分页 JOIN/级联软删。
 * 每个 @Test 在 @Transactional 内运行并回滚,数据互不干扰。
 */
class GroupLinkMapperDbTest extends DbTestBase {

    @Autowired
    private GroupLinkMapper mapper;

    @Autowired
    private GroupLinkLabelMapper labelMapper;

    @Autowired
    private GroupLinkImportBatchMapper batchMapper;

    @Autowired
    private GroupLinkPreviewMapper previewMapper;

    @Autowired
    private GroupLinkHealthMapper healthMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    // ---- 辅助方法 ----

    private GroupLinkLabel insertLabel(String name) {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setName(name);
        label.setRegion("印度");
        label.setRemark("测试分组");
        long now = System.currentTimeMillis();
        label.setCreatedAt(now);
        label.setUpdatedAt(now);
        labelMapper.insert(label);
        return label;
    }

    private GroupLinkImportBatch insertBatch(Long labelId, String fileName) {
        GroupLinkImportBatch batch = new GroupLinkImportBatch();
        batch.setLabelId(labelId);
        batch.setBatchName("测试批次");
        batch.setSourceFileName(fileName);
        batch.setCreatedAt(System.currentTimeMillis());
        batchMapper.insert(batch);
        return batch;
    }

    private GroupLink buildLink(String url, Long labelId, Long batchId) {
        GroupLink link = new GroupLink();
        link.setLinkUrl(url);
        link.setGroupName("测试群");
        link.setLabelId(labelId);
        link.setImportBatchId(batchId);
        long now = System.currentTimeMillis();
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        return link;
    }

    private String countByLabelSql(GroupLinkQuery query) {
        return sqlSessionFactory.getConfiguration()
                .getMappedStatement("com.armada.group.mapper.GroupLinkMapper.countByLabel")
                .getBoundSql(query)
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    // ---- 测试 ----

    @Test
    void uniqueUrlRejectsSecondActiveInsert() {
        GroupLinkLabel label = insertLabel("唯一键测试分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "test.txt");

        mapper.insert(buildLink("chat.whatsapp.com/AbcDef123", label.getId(), batch.getId()));
        assertThatThrownBy(() ->
                mapper.insert(buildLink("chat.whatsapp.com/AbcDef123", label.getId(), batch.getId())))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void upsertRevivesSoftDeleted() {
        GroupLinkLabel label1 = insertLabel("复活前分组");
        GroupLinkLabel label2 = insertLabel("复活后分组");
        GroupLinkImportBatch batch1 = insertBatch(label1.getId(), "batch1.txt");
        GroupLinkImportBatch batch2 = insertBatch(label2.getId(), "batch2.txt");

        // 1. 插入链接
        GroupLink link = buildLink("chat.whatsapp.com/ReviveTest", label1.getId(), batch1.getId());
        mapper.insert(link);
        assertThat(link.getId()).isNotNull();

        // 2. 软删
        mapper.softDeleteByIds(List.of(link.getId()), System.currentTimeMillis());

        // 3. selectAnyByUrl 命中(含软删)
        GroupLink found = mapper.selectAnyByUrl("chat.whatsapp.com/ReviveTest");
        assertThat(found).isNotNull();
        assertThat(found.getDeletedAt()).isNotNull();

        // 4. adoptToLabel 复活+改归属
        mapper.adoptToLabel(found.getId(), label2.getId(), batch2.getId(), "新群名", System.currentTimeMillis());

        // 5. 再次查询应为活跃且 label 已改
        GroupLink revived = mapper.selectAnyByUrl("chat.whatsapp.com/ReviveTest");
        assertThat(revived).isNotNull();
        assertThat(revived.getDeletedAt()).isNull();
        assertThat(revived.getLabelId()).isEqualTo(label2.getId());
        assertThat(revived.getGroupName()).isEqualTo("新群名");
    }

    @Test
    void selectActiveByIds_returnsOnlyActiveLinks() {
        GroupLinkLabel label = insertLabel("批量按ID查询分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "select-active-by-ids.txt");

        GroupLink active = buildLink("chat.whatsapp.com/SelectActiveByIdsA", label.getId(), batch.getId());
        GroupLink deleted = buildLink("chat.whatsapp.com/SelectActiveByIdsB", label.getId(), batch.getId());
        mapper.insert(active);
        mapper.insert(deleted);
        mapper.softDeleteByIds(List.of(deleted.getId()), System.currentTimeMillis());

        List<GroupLink> rows = mapper.selectActiveByIds(List.of(active.getId(), deleted.getId(), -1L));

        assertThat(rows).extracting(GroupLink::getId).containsExactly(active.getId());
    }

    @Test
    void adoptActiveIntoImport_setsImportOwnershipWithoutChangingOrigin() {
        GroupLinkLabel label = insertLabel("收编目标分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "adopt.txt");

        GroupLink link = buildLink("chat.whatsapp.com/AdoptFromPullTask", null, null);
        link.setOrigin(GroupLinkOrigin.PULL_TASK.code());
        link.setMembershipState(GroupMembershipState.TARGET.code());
        mapper.insert(link);

        int updated = mapper.adoptActiveIntoImport(
                link.getId(), label.getId(), batch.getId(), System.currentTimeMillis());
        assertThat(updated).isEqualTo(1);

        GroupLink after = mapper.selectAnyByUrl("chat.whatsapp.com/AdoptFromPullTask");
        assertThat(after.getLabelId()).isEqualTo(label.getId());
        assertThat(after.getImportBatchId()).isEqualTo(batch.getId());
        assertThat(after.getOrigin()).isEqualTo(GroupLinkOrigin.PULL_TASK.code());
    }

    @Test
    void selectPageByLabel_joinsSourceFileName() {
        GroupLinkLabel label = insertLabel("JOIN测试分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "source.xlsx");

        mapper.insert(buildLink("chat.whatsapp.com/JoinTest1", label.getId(), batch.getId()));
        mapper.insert(buildLink("chat.whatsapp.com/JoinTest2", label.getId(), batch.getId()));

        GroupLinkQuery query = new GroupLinkQuery();
        query.setLabelId(label.getId());
        query.setPage(1);
        query.setPageSize(10);

        long total = mapper.countByLabel(query);
        assertThat(total).isEqualTo(2);

        List<GroupLinkVoRow> rows = mapper.selectPageByLabel(query);
        assertThat(rows).hasSize(2);
        // 每行应 JOIN 出 sourceFileName
        rows.forEach(r -> assertThat(r.getSourceFileName()).isEqualTo("source.xlsx"));
    }

    @Test
    void selectPageByLabel_returnsWsGroupListProjectionAndFilters() {
        GroupLinkLabel label = insertLabel("群组列表主查询分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "main-query-source.xlsx");

        GroupLink link = buildLink("chat.whatsapp.com/MainQueryProjection", label.getId(), batch.getId());
        link.setGroupName(null);
        link.setOrigin(GroupLinkOrigin.IMPORT.code());
        link.setMembershipState(GroupMembershipState.JOINED.code());
        link.setRemark("运营备注");
        mapper.insert(link);

        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupLinkId(link.getId());
        preview.setGroupJid("1203630mainquery@g.us");
        preview.setInviteCode("MainQueryProjection");
        preview.setWaSubject("WA真实群名-主查询");
        preview.setMemberSize(42);
        preview.setOwnerPhone("8613800000011");
        preview.setAvatarUrl("https://cdn.example.com/group.png");
        preview.setLastPreviewAt(1_717_200_000_000L);
        preview.setCreatedAt(1_717_200_000_000L);
        preview.setUpdatedAt(1_717_200_000_000L);
        previewMapper.upsert(preview);

        GroupLinkHealth health = new GroupLinkHealth();
        health.setGroupLinkId(link.getId());
        health.setHealthStatus(GroupLinkHealthStatus.AVAILABLE.code());
        health.setBanned(false);
        health.setCurrentCount(45);
        health.setLastCheckAt(1_717_200_100_000L);
        health.setLastHealthError(null);
        health.setHealthFailureCount(0);
        health.setCreatedAt(1_717_200_100_000L);
        health.setUpdatedAt(1_717_200_100_000L);
        healthMapper.upsert(health);

        jdbc.update("""
                INSERT INTO join_task_result
                    (tenant_id, join_task_id, account, account_id, link, status, reason,
                     group_jid, is_admin, promoted_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TEST_TENANT_ID, 990001L, "8611111111111", 101L, link.getLinkUrl(), "SUCCESS", "",
                "1203630mainquery@g.us", 1, 1_717_200_200_000L, 1_717_200_000_000L, 1_717_200_200_000L,
                TEST_TENANT_ID, 990002L, "8622222222222", 102L, link.getLinkUrl(), "SUCCESS", "",
                "1203630mainquery@g.us", 1, 1_717_200_300_000L, 1_717_200_000_000L, 1_717_200_300_000L);

        GroupLinkQuery query = new GroupLinkQuery();
        query.setKeyword("WA真实群名-主查询");
        query.setStatus("AVAILABLE");
        query.setSourceFileName("main-query-source.xlsx");
        query.setOrigin(GroupLinkOrigin.IMPORT.code());
        query.setPage(1);
        query.setPageSize(10);

        assertThat(mapper.countByLabel(query)).isEqualTo(1);
        List<GroupLinkVoRow> rows = mapper.selectPageByLabel(query);

        assertThat(rows).hasSize(1);
        GroupLinkVoRow row = rows.get(0);
        assertThat(row.getId()).isEqualTo(link.getId());
        assertThat(row.getGroupName()).isEqualTo("WA真实群名-主查询");
        assertThat(row.getUrl()).isEqualTo("chat.whatsapp.com/MainQueryProjection");
        assertThat(row.getSourceFileName()).isEqualTo("main-query-source.xlsx");
        assertThat(row.getGroupJid()).isEqualTo("1203630mainquery@g.us");
        assertThat(row.getAvatarUrl()).isEqualTo("https://cdn.example.com/group.png");
        assertThat(row.getOwnerPhone()).isEqualTo("8613800000011");
        assertThat(row.getHealthStatus()).isEqualTo(GroupLinkHealthStatus.AVAILABLE.code());
        assertThat(row.getBanned()).isFalse();
        assertThat(row.getMemberSize()).isEqualTo(42);
        assertThat(row.getCurrentCount()).isEqualTo(45);
        assertThat(row.getAdmin()).isEqualTo("8611111111111, 8622222222222");
        assertThat(row.getOrigin()).isEqualTo(GroupLinkOrigin.IMPORT.code());
        assertThat(row.getMembershipState()).isEqualTo(GroupMembershipState.JOINED.code());
        assertThat(row.getRemark()).isEqualTo("运营备注");
        assertThat(row.getLastPreviewAt()).isEqualTo(1_717_200_000_000L);
        assertThat(row.getLastCheckAt()).isEqualTo(1_717_200_100_000L);

        query.setKeyword("8622222222222");
        assertThat(mapper.countByLabel(query)).isEqualTo(1);
        assertThat(mapper.selectPageByLabel(query)).hasSize(1);
    }

    @Test
    void countByLabel_withoutKeyword_doesNotBuildAdminAggregation() {
        GroupLinkQuery query = new GroupLinkQuery();
        query.setPage(1);
        query.setPageSize(10);

        String sql = countByLabelSql(query);

        assertThat(sql).doesNotContain("join_task_result");
        assertThat(sql).doesNotContain("group_concat");
    }

    @Test
    void countByLabel_withKeywordSearchesAdminWithoutGroupConcatAggregation() {
        GroupLinkQuery query = new GroupLinkQuery();
        query.setKeyword("8611111111111");
        query.setPage(1);
        query.setPageSize(10);

        String sql = countByLabelSql(query);

        assertThat(sql).contains("exists");
        assertThat(sql).contains("join_task_result");
        assertThat(sql).doesNotContain("group_concat");
    }

    @Test
    void softDeleteByLabelIds_cascadesSoftDeletesLinks() {
        GroupLinkLabel label = insertLabel("级联删除分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "cascade.txt");

        mapper.insert(buildLink("chat.whatsapp.com/CascadeTest1", label.getId(), batch.getId()));
        mapper.insert(buildLink("chat.whatsapp.com/CascadeTest2", label.getId(), batch.getId()));

        // 级联软删
        int deleted = mapper.softDeleteByLabelIds(List.of(label.getId()), System.currentTimeMillis());
        assertThat(deleted).isEqualTo(2);

        // 分页查不到(活跃=0)
        GroupLinkQuery query = new GroupLinkQuery();
        query.setLabelId(label.getId());
        query.setPage(1);
        query.setPageSize(10);

        assertThat(mapper.countByLabel(query)).isEqualTo(0);
    }
}
