package com.armada.task.service;

import com.armada.task.model.dto.PullTaskCommandCallback;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;

/** 普通群链接执行域接收协议异步结果的应用边界。 */
public interface PullTaskProtocolResultCallbackService {

    /** 按 commandId 收敛联系人、邀请或踩链接动作。 */
    boolean handleAccountAction(PullTaskCommandCallback callback);

    /** 按 commandId、调用 ID 和目标 JID 收敛一次批量拉人的单成员结果。 */
    boolean handlePullCallParticipant(PullTaskBatchParticipantCallback callback);

    /** 按任务、执行行、料子、命令和目标 JID 收敛单个料子的管理员提权结果。 */
    boolean handleMaterialAdmin(PullTaskMaterialAdminCallback callback);
}
