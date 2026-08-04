package com.armada.task.service.impl;

import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import org.springframework.stereotype.Component;

/** 普通群链接详情的角色、料子、调用和账号动作事实入口。 */
@Component
public record PullTaskStandardReadFactMappers(
        PullTaskGroupAccountMapper accountMapper,
        PullTaskMaterialMemberMapper materialMapper,
        PullTaskPullCallMapper callMapper,
        PullTaskAccountActionMapper actionMapper) {
}
