package com.armada.marketing.grouppull.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.marketing.grouppull.model.entity.GroupPullMarketingMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullBlockReason;
import com.armada.marketing.grouppull.model.enums.GroupPullMaterialStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullSpeakPermission;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 拉群营销基础 Mapper 真库读写测试。 */
class GroupPullMarketingMapperDbTest extends DbTestBase {

    @Autowired
    private GroupPullMarketingMapper mapper;

    @Test
    void mapperRoundTripsTaskAndReturnsMaterialsInFileOrder() {
        long taskId = System.currentTimeMillis();
        GroupPullMarketingTask task = task(taskId);

        assertThat(mapper.insertTask(task)).isEqualTo(1);
        assertThat(mapper.insertMaterials(List.of(
                material(taskId, 2, "8613800000002"),
                material(taskId, 1, "8613800000001")))).isEqualTo(2);

        assertThat(mapper.selectTaskById(taskId).getBuilderGroupId()).isEqualTo(101L);
        assertThat(mapper.selectAvailableMaterialsForUpdate(taskId, 2))
                .extracting(GroupPullMarketingMaterial::getLineNo)
                .containsExactly(1, 2);
    }

    private GroupPullMarketingTask task(long taskId) {
        long now = System.currentTimeMillis();
        GroupPullMarketingTask task = new GroupPullMarketingTask();
        task.setMarketingTaskId(taskId);
        task.setTenantId(TEST_TENANT_ID);
        task.setBuilderGroupId(101L);
        task.setMarketingAccountGroupLimit(10);
        task.setFriendRetryLimit(3);
        task.setMaterialPerGroup(3);
        task.setSpeakPermission(GroupPullSpeakPermission.UNCHANGED.code());
        task.setBuilderExitEnabled(true);
        task.setBlockReason(GroupPullBlockReason.NONE.code());
        task.setResourceStatus(GroupPullResourceStatus.UNLOCKED.code());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private GroupPullMarketingMaterial material(long taskId, int lineNo, String phone) {
        long now = System.currentTimeMillis();
        GroupPullMarketingMaterial material = new GroupPullMarketingMaterial();
        material.setTenantId(TEST_TENANT_ID);
        material.setTaskId(taskId);
        material.setLineNo(lineNo);
        material.setPhone(phone);
        material.setStatus(GroupPullMaterialStatus.AVAILABLE.code());
        material.setCreatedAt(now);
        material.setUpdatedAt(now);
        return material;
    }
}
