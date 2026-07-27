package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.marketing.grouppull.model.dto.CreateGroupPullMarketingTaskDTO;
import com.armada.marketing.grouppull.service.impl.GroupPullMarketingTaskServiceImpl;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

/** 拉群营销任务逐料间隔配置校验测试。 */
class GroupPullMarketingTaskConfigurationTest {

    @Test
    void rejectsMaterialEntryIntervalOutsideWholeMinuteRangeBeforeAccessingDependencies() {
        GroupPullMarketingTaskServiceImpl service =
                new GroupPullMarketingTaskServiceImpl(
                        null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(request(59), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("拉料间隔必须是1到60的整数分钟");
    }

    private CreateGroupPullMarketingTaskDTO request(Integer intervalSeconds) {
        return new CreateGroupPullMarketingTaskDTO(
                "间隔测试",
                1L,
                null,
                null,
                2L,
                10,
                3L,
                30,
                null,
                3,
                3,
                intervalSeconds,
                1,
                true,
                null,
                System.currentTimeMillis() + 60_000L);
    }
}
