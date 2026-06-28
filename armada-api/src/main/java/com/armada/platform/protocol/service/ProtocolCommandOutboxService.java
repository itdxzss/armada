package com.armada.platform.protocol.service;

import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.exception.BusinessException;
import java.util.List;

/**
 * 协议命令 Outbox 应用服务。
 *
 * <p>本服务只负责把账号生命周期命令转换为本地 outbox 行。它不发送 Kafka、不启动调度,
 * Kafka 发送由事务提交后的 dispatcher 触发。</p>
 */
public interface ProtocolCommandOutboxService {

    /**
     * 批量写入账号上线 outbox 命令。
     *
     * @param commands 待 enqueue 的账号上线命令,最多 500 条
     * @return 本次生成的批次 ID、命令 ID 和插入行数
     * @throws BusinessException 入参非法或 command_id 冲突时抛出
     */
    ProtocolCommandOutboxEnqueueResult enqueueOnlineCommands(List<ProtocolOnlineCommandRequest> commands);

    /**
     * 批量写入账号下线 outbox 命令。
     *
     * @param commands 待 enqueue 的账号下线命令,最多 500 条
     * @return 本次生成的批次 ID、命令 ID 和插入行数
     * @throws BusinessException 入参非法或 command_id 冲突时抛出
     */
    ProtocolCommandOutboxEnqueueResult enqueueOfflineCommands(List<ProtocolOfflineCommandRequest> commands);
}
