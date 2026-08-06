package com.armada.group.normalcreation.model.vo;

import java.util.List;

/** 新建普群任务及全部计划群明细。 */
public record NormalGroupCreationTaskDetailVO(
        NormalGroupCreationTaskVO task,
        List<NormalGroupCreationItemVO> items) {
}
