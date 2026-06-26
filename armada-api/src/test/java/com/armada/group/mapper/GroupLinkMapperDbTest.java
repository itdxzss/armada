package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkImportBatch;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupLinkVoRow;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

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
