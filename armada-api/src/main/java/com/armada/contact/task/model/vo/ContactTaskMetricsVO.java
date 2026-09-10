package com.armada.contact.task.model.vo;

/**
 * 单次读取快照中的发送记录统计，送达和已读为发送确认的子集。
 *
 * @param scopeId 聚合范围 ID，任务查询为 taskId，账号查询为 taskAccountId
 * @param plannedNum 已固化记录数
 * @param attemptedNum 发起过处理的记录数，包含本地拒绝，不代表网络派发
 * @param confirmedNum 至少获得发送确认的记录数
 * @param deliveredNum 至少获得送达确认的记录数
 * @param readNum 获得已读确认的记录数
 * @param failedNum 明确失败记录数
 * @param unknownNum 结果未知记录数
 * @param skippedNum 未执行而跳过的记录数
 * @param processedNum 已终结自动处理的记录数
 * @param pendingNum 待处理记录数
 * @param sendingNum 处理中记录数
 * @param inconsistentNum 状态与回执矛盾或未知状态的记录数，不可用于展示正常比率
 */
public record ContactTaskMetricsVO(Long scopeId, long plannedNum, long attemptedNum,
        long confirmedNum, long deliveredNum, long readNum, long failedNum,
        long unknownNum, long skippedNum, long processedNum, long pendingNum,
        long sendingNum, long inconsistentNum) {
    /** 已验证的查询范围没有 recipient 时返回真实空集统计。 */
    public static ContactTaskMetricsVO empty(Long scopeId) {
        return new ContactTaskMetricsVO(scopeId, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
