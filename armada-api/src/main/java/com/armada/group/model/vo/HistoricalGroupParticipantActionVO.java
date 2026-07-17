package com.armada.group.model.vo;

import java.util.List;

/**
 * 历史群批量成员动作的逐项结果。
 *
 * @param ok      是否全部目标均操作成功
 * @param partial 是否同时存在成功与失败目标
 * @param results 按请求目标顺序返回的完整结果
 */
public record HistoricalGroupParticipantActionVO(
        boolean ok,
        boolean partial,
        List<Result> results) {

    /**
     * 单个成员动作结果。
     *
     * @param participantJid 目标成员完整 JID
     * @param success        是否操作成功
     * @param status         协议层稳定状态
     * @param errorCode      失败错误码
     * @param errorMessage   完整失败信息
     */
    public record Result(
            String participantJid,
            boolean success,
            String status,
            String errorCode,
            String errorMessage) {
    }
}
