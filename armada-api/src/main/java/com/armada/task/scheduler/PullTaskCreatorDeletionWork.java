package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.task.model.entity.PullTaskCreatorDeletion;
import com.armada.task.model.entity.PullTaskGroupExecution;

/** 一次有界协议调用绑定的持久化身份与租约。 */
public record PullTaskCreatorDeletionWork(PullTaskGroupExecution execution,
        PullTaskCreatorDeletion deletion, ProtocolAccountRef creator, ProtocolAccountRef manager) { }
