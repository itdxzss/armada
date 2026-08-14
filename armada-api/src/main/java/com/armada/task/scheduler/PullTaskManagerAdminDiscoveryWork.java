package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import java.util.List;

/** 管理员本地事实缺失时的一次定点群成员查询工作。 */
public record PullTaskManagerAdminDiscoveryWork(
        long tenantId,
        long taskId,
        long executionId,
        int expectedVersion,
        String lockOwner,
        String groupJid,
        long managerRoleId,
        ProtocolAccountRef actor,
        List<String> targetJids) {

    public PullTaskManagerAdminDiscoveryWork {
        targetJids = targetJids == null ? List.of() : List.copyOf(targetJids);
    }

    /** 同一任务管理员只允许创建一条稳定 discovery 业务链。 */
    public String businessKey() {
        return "manager-admin-discovery:" + managerRoleId;
    }
}
