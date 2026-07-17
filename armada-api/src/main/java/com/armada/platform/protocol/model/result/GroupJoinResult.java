package com.armada.platform.protocol.model.result;

/**
 * 与具体协议响应格式无关的进群结果。
 *
 * @param groupJid 群 JID；协议未返回时为空字符串
 * @param outcome 统一进群结果
 */
public record GroupJoinResult(String groupJid, GroupJoinOutcome outcome) {

    public GroupJoinResult {
        groupJid = groupJid == null ? "" : groupJid.trim();
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为空");
        }
    }

    /**
     * 判断账号是否已经真实处于群内。
     *
     * @return 本次已入群或请求前已在群内时返回 true
     */
    public boolean joined() {
        return outcome == GroupJoinOutcome.JOINED || outcome == GroupJoinOutcome.ALREADY_JOINED;
    }
}
