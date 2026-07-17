package com.armada.platform.protocol.port;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import java.util.List;

/**
 * 协议层账号当前参与群实时查询端口。
 */
public interface AccountParticipatingGroupPort {

    /**
     * 查询固定协议账号当前参与群的轻量列表。
     *
     * @param account 固定操作账号引用
     * @return 仅包含群 JID 和群名称的当前群列表
     * @throws ProtocolException 当协议响应缺失或协议调用失败时抛出
     */
    List<AccountParticipatingGroupResult.Group> listCurrent(ProtocolAccountRef account);

    /**
     * 批量查询固定协议账号在指定群中的 metadata 摘要。
     *
     * <p>返回顺序与协议层逐项响应一致；单群失败通过结果中的 {@code success/error}
     * 保留，不折叠成整个批次失败。</p>
     *
     * @param account     固定操作账号引用
     * @param groupJids   待查询的 WhatsApp 群 JID，顺序需要保留
     * @param concurrency 协议层批量查询并发数
     * @return 不包含参与者明细的逐群 metadata 摘要
     * @throws ProtocolException 当请求参数、顶层响应或协议调用失败时抛出
     */
    List<AccountGroupMetadataSummaryResult> summarize(
            ProtocolAccountRef account,
            List<String> groupJids,
            int concurrency);

    /**
     * 批量查询多个协议账号当前参与的群。
     *
     * @param protocolAccountIds 协议层账号句柄
     * @param concurrency        单次协议请求并发数,最终由协议服务自行限制
     * @return 协议层返回的逐账号查群结果
     */
    List<AccountParticipatingGroupResult> listBatch(List<String> protocolAccountIds, int concurrency);
}
