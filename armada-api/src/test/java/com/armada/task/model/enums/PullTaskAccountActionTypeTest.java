package com.armada.task.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PullTaskAccountActionTypeTest {

    @Test
    @DisplayName("放开加人权限动作使用数据库取值 5")
    void openMemberAddUsesCodeFive() {
        assertThat(PullTaskAccountActionType.OPEN_MEMBER_ADD.code()).isEqualTo(5);
    }

    @Test
    @DisplayName("关闭进群审核动作使用数据库取值 6")
    void closeJoinApprovalUsesCodeSix() {
        assertThat(PullTaskAccountActionType.CLOSE_JOIN_APPROVAL.code()).isEqualTo(6);
    }

    @Test
    @DisplayName("存量动作类型取值不被新增动作占用")
    void existingActionCodesRemainStable() {
        assertThat(PullTaskAccountActionType.SAVE_CONTACT.code()).isEqualTo(1);
        assertThat(PullTaskAccountActionType.INVITE_TO_GROUP.code()).isEqualTo(2);
        assertThat(PullTaskAccountActionType.JOIN_BY_LINK.code()).isEqualTo(3);
        assertThat(PullTaskAccountActionType.PROMOTE_MANAGER.code()).isEqualTo(4);
    }

    @Test
    @DisplayName("所有动作类型的数据库取值互不重复")
    void everyActionCodeIsUnique() {
        long distinct = Arrays.stream(PullTaskAccountActionType.values())
                .map(PullTaskAccountActionType::code)
                .collect(Collectors.toSet())
                .size();

        assertThat(distinct).isEqualTo(PullTaskAccountActionType.values().length);
    }
}
