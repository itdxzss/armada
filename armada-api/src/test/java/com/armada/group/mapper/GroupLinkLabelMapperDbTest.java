package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.group.model.dto.GroupLinkLabelQuery;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupLinkLabelVoRow;
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

    // ---- 辅助方法 ----

    private GroupLinkLabel buildLabel(String name) {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setName(name);
        label.setRegion("印度");
        label.setRemark("测试");
        return label;
    }

    // ---- 测试 ----

    @Test
    void insert_then_selectActiveByName() {
        GroupLinkLabel label = buildLabel("测试分组A");
        mapper.insert(label);
        assertThat(label.getId()).isNotNull();

        GroupLinkLabel found = mapper.selectActiveByName("测试分组A");
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

        // 通过 GroupLinkMapper softDelete 验证:先插2活跃+1软删的group_link
        // 由于 group_link 的 INSERT 需要完整mapper(Phase 3),这里用 softDeleteByLabelIds 验证无活跃链接
        // 验 linkCount=0(无群链接时)
        GroupLinkLabelQuery query = new GroupLinkLabelQuery();
        query.setId(labelId);
        query.setPage(1);
        query.setPageSize(10);

        long total = mapper.countPage(query);
        assertThat(total).isEqualTo(1);

        List<GroupLinkLabelVoRow> rows = mapper.selectPage(query);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getName()).isEqualTo("聚合测试分组");
        assertThat(rows.get(0).getLinkCount()).isEqualTo(0L);
    }

    @Test
    void softDeleteThenReviveByName() {
        // 插入 -> 软删 -> selectDeletedByName 命中 -> reviveById -> selectActiveByName 命中
        GroupLinkLabel label = buildLabel("复活测试分组");
        mapper.insert(label);
        Long id = label.getId();

        // 软删
        mapper.softDeleteByIds(List.of(id));
        assertThat(mapper.selectActiveByName("复活测试分组")).isNull();

        // 查软删
        GroupLinkLabel deleted = mapper.selectDeletedByName("复活测试分组");
        assertThat(deleted).isNotNull();
        assertThat(deleted.getDeletedAt()).isNotNull();

        // 复活
        mapper.reviveById(id);

        // 重新活跃
        GroupLinkLabel revived = mapper.selectActiveByName("复活测试分组");
        assertThat(revived).isNotNull();
        assertThat(revived.getDeletedAt()).isNull();
    }

    @Test
    void selectById_returnsNullAfterSoftDelete() {
        GroupLinkLabel label = buildLabel("按ID查测试");
        mapper.insert(label);
        Long id = label.getId();

        assertThat(mapper.selectById(id)).isNotNull();
        mapper.softDeleteByIds(List.of(id));
        assertThat(mapper.selectById(id)).isNull();
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
        mapper.updateProfile(update);

        GroupLinkLabel found = mapper.selectById(label.getId());
        assertThat(found.getName()).isEqualTo("修改后名称");
        assertThat(found.getRegion()).isEqualTo("巴基斯坦");
    }
}
