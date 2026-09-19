package com.armada.task.model.dto;

import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskApproval;
import com.armada.task.model.entity.JoinTaskResult;

/** 当前短事务领取的审核处理快照，不携带数据库锁。 */
public record JoinTaskApprovalWork(JoinTask task, JoinTaskResult result, JoinTaskApproval approval) {}
