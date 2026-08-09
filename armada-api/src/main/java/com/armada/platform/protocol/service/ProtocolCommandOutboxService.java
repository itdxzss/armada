package com.armada.platform.protocol.service;

import com.armada.platform.protocol.model.command.ProtocolAccountGroupSyncCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolGroupHealthCheckCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolGroupJoinCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolMessageOutboxCommand;
import com.armada.platform.protocol.model.command.ProtocolNormalGroupCreationCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupJoinCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskContactSaveCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMaterialAdminCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMemberQueryCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskManagerAdminCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskPullerInviteCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskBatchAddCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.exception.BusinessException;
import java.util.List;

/**
 * 协议命令 Outbox 应用服务。
 *
 * <p>本服务把账号、群组和营销业务命令转换为本地 outbox 行。它不在业务事务内发送 Kafka；
 * Kafka 发送由事务提交后的通用 dispatcher 触发，避免协议层执行一条最终被数据库回滚的命令。</p>
 */
public interface ProtocolCommandOutboxService {

    /**
     * 批量写入账号上线 outbox 命令。
     *
     * @param commands 待 enqueue 的账号上线命令,最多 1000 条
     * @return 本次生成的批次 ID、命令 ID 和插入行数
     * @throws BusinessException 入参非法或 command_id 冲突时抛出
     */
    ProtocolCommandOutboxEnqueueResult enqueueOnlineCommands(List<ProtocolOnlineCommandRequest> commands);

    /**
     * 批量写入账号下线 outbox 命令。
     *
     * @param commands 待 enqueue 的账号下线命令,最多 1000 条
     * @return 本次生成的批次 ID、命令 ID 和插入行数
     * @throws BusinessException 入参非法或 command_id 冲突时抛出
     */
    ProtocolCommandOutboxEnqueueResult enqueueOfflineCommands(List<ProtocolOfflineCommandRequest> commands);

    /**
     * 取消当前租户下尚未发布的账号上线命令。
     *
     * <p>显式下线调用该方法阻止旧 PENDING 上线命令在下线之后才发布；LOCKED/SENT 命令保持原状，
     * 依赖同账号 Kafka key 保序由更新的下线命令收口。</p>
     *
     * @param accountIds 显式下线的账号 ID，最多 1000 个
     * @return 实际取消的 PENDING 上线命令数
     * @throws BusinessException 账号 ID 非法或超过批量上限时抛出
     */
    int cancelPendingAccountOnlineCommands(List<Long> accountIds);

    /**
     * 取消普通拉群任务或单条执行行尚未提交发送的命令。
     *
     * @param taskId 普通拉群任务 ID
     * @param executionId 单群执行行 ID；为空时取消整个任务
     * @param now 取消时间(epoch 毫秒)
     * @return 实际处理的命令数；PENDING/LOCKED 进入 CANCELED，DISPATCHING 进入 CANCEL_REQUESTED
     */
    int cancelPendingPullTaskCommands(long taskId, Long executionId, long now);

    /**
     * 批量写入群链接健康检查 outbox 命令。
     *
     * @param commands 待 enqueue 的群健康检查命令,最多 500 条
     * @return 本次生成的批次 ID、命令 ID 和插入行数
     * @throws BusinessException 入参非法或 command_id 冲突时抛出
     */
    ProtocolCommandOutboxEnqueueResult enqueueGroupHealthCheckCommands(
            List<ProtocolGroupHealthCheckCommandRequest> commands);

    /**
     * 批量写入账号当前群同步 outbox 命令。
     *
     * @param commands 待 enqueue 的账号群同步命令,最多 500 条
     * @return 本次生成的批次 ID、命令 ID 和插入行数
     * @throws BusinessException 入参非法或 command_id 冲突时抛出
     */
    ProtocolCommandOutboxEnqueueResult enqueueAccountGroupSyncCommands(
            List<ProtocolAccountGroupSyncCommandRequest> commands);

    /**
     * 批量写入 Web/Android 统一进群 outbox 命令。
     *
     * <p>每条命令按账号冻结的协议后端路由到 Web master 或 Android topic，Kafka key 固定为协议账号
     * ID。调用方必须在同一事务内把对应进群明细切换为 SUBMITTED，保证业务状态与 outbox 原子提交。</p>
     *
     * @param commands 已完成账号和群链接前置校验的进群命令，最多 500 条
     * @return 命令 ID 与插入数量；同一任务批次返回稳定的任务 batchId
     * @throws BusinessException 批次为空、超限、字段非法、commandId 冲突或插入数量不一致时抛出
     */
    ProtocolCommandOutboxEnqueueResult enqueueGroupJoinCommands(
            List<ProtocolGroupJoinCommandRequest> commands);

    /**
     * 批量写入普通群链接管理员踩链接 Outbox 命令。
     *
     * <p>调用方必须在同一事务内把对应动作改为 SUBMITTED，并把返回的真实 commandId 写回动作行。
     * Outbox payload 仅保存业务引用，协议执行参数由发布器从冻结事实补全。</p>
     *
     * @param commands 普通拉群管理员踩链接命令，最多 500 条
     * @return 稳定任务批次、命令 ID 与插入数量
     * @throws BusinessException 字段非法、租户不一致或 Outbox 写入失败时抛出
     */
    ProtocolCommandOutboxEnqueueResult enqueuePullTaskGroupJoinCommands(
            List<ProtocolPullTaskGroupJoinCommandRequest> commands);

    /**
     * 批量写入普通拉群单方向联系人保存命令。
     *
     * @param commands 联系人动作引用，最多 500 条
     * @return 稳定任务批次、命令 ID 与插入数量
     */
    ProtocolCommandOutboxEnqueueResult enqueuePullTaskContactSaveCommands(
            List<ProtocolPullTaskContactSaveCommandRequest> commands);

    /**
     * 批量写入普通拉群管理员单人邀请拉手命令。
     *
     * @param commands 邀请动作引用，最多 500 条
     * @return 稳定任务批次、命令 ID 与插入数量
     */
    ProtocolCommandOutboxEnqueueResult enqueuePullTaskPullerInviteCommands(
            List<ProtocolPullTaskPullerInviteCommandRequest> commands);

    /**
     * 批量写入普通拉群既有管理员提权任务管理员命令。
     *
     * @param commands 管理员设置动作引用，最多 500 条
     * @return 稳定任务批次、命令 ID 与插入数量
     */
    ProtocolCommandOutboxEnqueueResult enqueuePullTaskManagerAdminCommands(
            List<ProtocolPullTaskManagerAdminCommandRequest> commands);

    /** 批量写入普通拉群站台和料子同批入群命令。 */
    ProtocolCommandOutboxEnqueueResult enqueuePullTaskBatchAddCommands(
            List<ProtocolPullTaskBatchAddCommandRequest> commands);

    /** 批量写入普通拉群单个 A/a 料子提权命令。 */
    ProtocolCommandOutboxEnqueueResult enqueuePullTaskMaterialAdminCommands(
            List<ProtocolPullTaskMaterialAdminCommandRequest> commands);

    /** 写入普通拉群群成员事实查询命令。 */
    ProtocolCommandOutboxEnqueueResult enqueuePullTaskMemberQueryCommands(
            List<ProtocolPullTaskMemberQueryCommandRequest> commands);

    /**
     * 批量写入新建普群协议动作命令。
     *
     * <p>每条命令严格按实际执行账号的 backend 路由：WEB 只进 master Topic，ANDROID
     * 只进 Android group-action Topic。联系人准备的两个方向因此可以落到不同协议 Topic。</p>
     */
    ProtocolCommandOutboxEnqueueResult enqueueNormalGroupCreationCommands(
            List<ProtocolNormalGroupCreationCommandRequest> commands);

    /**
     * 批量写入已经由协议 backend 编码的营销消息 outbox 命令。
     *
     * @param commands backend 已选择 topic、key 和 wire payload 的消息命令，最多 500 条
     * @return 本次生成的批次 ID、命令 ID 和插入行数
     * @throws BusinessException 入参非法或 command_id 冲突时抛出
     */
    ProtocolCommandOutboxEnqueueResult enqueueMessageCommands(
            List<ProtocolMessageOutboxCommand> commands);
}
