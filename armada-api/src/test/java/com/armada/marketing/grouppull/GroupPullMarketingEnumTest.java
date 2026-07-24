package com.armada.marketing.grouppull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.marketing.grouppull.model.enums.GroupPullBlockReason;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullMaterialStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullSpeakPermission;
import com.armada.marketing.model.enums.MarketingBusinessType;
import org.junit.jupiter.api.Test;

/** 拉群营销持久化码值稳定性测试。 */
class GroupPullMarketingEnumTest {

    @Test
    void persistentCodesRemainStable() {
        assertThat(MarketingBusinessType.ORDINARY.code()).isEqualTo(1);
        assertThat(MarketingBusinessType.GROUP_PULL.code()).isEqualTo(2);
        assertThat(GroupPullBlockReason.NONE.code()).isZero();
        assertThat(GroupPullResourceStatus.UNLOCKED.code()).isEqualTo(1);
        assertThat(GroupPullResourceStatus.RELEASED.code()).isEqualTo(4);
        assertThat(GroupPullExecutionStatus.MANUAL_REVIEW.code()).isEqualTo(7);
        assertThat(GroupPullExecutionStage.RESOURCE_PREPARATION.code()).isEqualTo(1);
        assertThat(GroupPullExecutionStage.COMPLETED.code()).isEqualTo(11);
        assertThat(GroupPullMaterialStatus.USED_BY_FAILED_GROUP.code()).isEqualTo(4);
        assertThat(GroupPullSpeakPermission.UNMUTED.code()).isEqualTo(3);
    }

    @Test
    void unknownPersistentCodesAreRejected() {
        assertThatThrownBy(() -> MarketingBusinessType.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GroupPullExecutionStage.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GroupPullResourceStatus.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GroupPullResourceStatus.fromCode(5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
