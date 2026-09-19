package com.armada.task.model.dto;

import com.armada.task.model.entity.JoinTaskCleanup;
import com.armada.task.model.entity.JoinTaskResult;

/** 事务提交后执行网络操作需要的固定阶段与账号关联。 */
public record JoinTaskCleanupWork(JoinTaskCleanup cleanup, JoinTaskResult result) { }
