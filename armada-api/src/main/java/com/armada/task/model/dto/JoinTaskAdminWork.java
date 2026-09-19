package com.armada.task.model.dto;

import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;

/** 短事务领取后的管理员阶段快照，nextExecuteAt 作为租约版本。 */
public record JoinTaskAdminWork(JoinTask task, JoinTaskResult row) { }
