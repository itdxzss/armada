package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;

import java.util.List;

/**
 * 单一协议后端的固定账号当前参与群读取能力。
 */
public interface AccountParticipatingGroupBackend {

    /**
     * 返回当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 查询固定账号当前参与群。
     *
     * @param account 固定操作账号引用
     * @return 当前群轻量列表
     */
    List<AccountParticipatingGroupResult.Group> listCurrent(ProtocolAccountRef account);

    /**
     * 查询固定账号的逐群摘要。
     *
     * @param account 固定操作账号引用
     * @param groupJids 待查询群 JID
     * @param concurrency 并发提示
     * @return 逐群摘要
     */
    List<AccountGroupMetadataSummaryResult> summarize(
            ProtocolAccountRef account,
            List<String> groupJids,
            int concurrency);
}
