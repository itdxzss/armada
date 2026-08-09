package com.armada.task.service.impl;

import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import org.springframework.stereotype.Component;

/** 聚合群级失败时需要释放的料子和角色账号持久化依赖。 */
@Component
public record PullTaskGroupExecutionFailureParticipants(
        PullTaskMaterialMemberMapper materialMapper,
        PullTaskGroupAccountMapper accountMapper) {
}
