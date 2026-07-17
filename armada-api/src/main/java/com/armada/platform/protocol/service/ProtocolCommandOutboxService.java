package com.armada.platform.protocol.service;

import com.armada.platform.protocol.model.command.ProtocolAccountGroupSyncCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolGroupHealthCheckCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolGroupJoinCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolMessageOutboxCommand;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
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
     * 批量写入已经由协议 backend 编码的营销消息 outbox 命令。
     *
     * @param commands backend 已选择 topic、key 和 wire payload 的消息命令，最多 500 条
     * @return 本次生成的批次 ID、命令 ID 和插入行数
     * @throws BusinessException 入参非法或 command_id 冲突时抛出
     */
    ProtocolCommandOutboxEnqueueResult enqueueMessageCommands(
            List<ProtocolMessageOutboxCommand> commands);
}
