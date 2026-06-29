package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.result.GroupJoinResult;

/**
 * 群入群协议端口。
 *
 * <p>业务域只依赖本端口,不直接拼协议层 HTTP URL/body。后续切换传输或协议层字段时只改 adapter。</p>
 */
public interface GroupJoinPort {

    /**
     * 指定账号通过邀请链接或 invite code 入群。
     *
     * @param protocolAccountId 协议层账号句柄,如 acc_8613800138000
     * @param inviteCodeOrLink  完整邀请链接或纯 invite code
     * @return 协议层 join 结果;{@code joined=false} 代表待审批,不是成功入群
     */
    GroupJoinResult join(String protocolAccountId, String inviteCodeOrLink);
}
