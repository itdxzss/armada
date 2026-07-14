package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.result.GroupJoinResult;

/**
 * 群入群协议端口。
 *
 * <p>业务域只依赖本端口,不直接拼协议层 HTTP URL/body。后续切换传输或协议层字段时只改 adapter。</p>
 */
public interface GroupJoinPort {

    /**
     * 执行统一进群命令。
     *
     * @param command 包含账号、邀请信息和业务操作标识的统一命令
     * @return 统一进群结果
     */
    GroupJoinResult join(GroupJoinCommand command);
}
