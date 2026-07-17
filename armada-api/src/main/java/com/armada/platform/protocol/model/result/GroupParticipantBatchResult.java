package com.armada.platform.protocol.model.result;

import java.util.List;

/**
 * 协议层群成员批量操作结果。
 *
 * @param partial 回执是否短于请求成员列表或发生超时
 * @param results 已收到的逐成员结果
 */
public record GroupParticipantBatchResult(
        boolean partial,
        List<Item> results) {

    /** 单个成员的协议操作结果。 */
    public record Item(String jid, String status, String rawStatus) {
    }
}
