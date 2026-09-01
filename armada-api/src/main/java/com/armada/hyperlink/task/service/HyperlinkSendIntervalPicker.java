package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import java.util.SplittableRandom;

/** 为每条超链 recipient 选择稳定且均匀的发送间隔。 */
public final class HyperlinkSendIntervalPicker {

    private HyperlinkSendIntervalPicker() {
    }

    /**
     * 在任务配置的闭区间内为 recipient 生成稳定的伪随机毫秒间隔。
     *
     * <p>recipient 主键作为固定种子，既打散连续主键的窄带递增特征，也保证事务重试或换号重发
     * 不会改变同一条 recipient 已选中的业务间隔。</p>
     *
     * @param task 超链任务，包含毫秒级间隔上下限
     * @param recipientId recipient 主键
     * @return 位于配置闭区间内的毫秒间隔
     * @throws IllegalArgumentException 任务、recipient 主键或间隔配置非法时抛出
     */
    public static int pickMs(HyperlinkTask task, long recipientId) {
        if (task == null || recipientId <= 0L
                || task.getMsgIntervalMinMs() == null || task.getMsgIntervalMaxMs() == null) {
            throw new IllegalArgumentException("超链任务和 recipient 间隔参数不能为空");
        }
        int minMs = task.getMsgIntervalMinMs();
        int maxMs = task.getMsgIntervalMaxMs();
        if (minMs < 0 || maxMs < minMs) {
            throw new IllegalArgumentException("超链发送间隔配置无效");
        }
        if (minMs == maxMs) {
            return minMs;
        }
        return (int) new SplittableRandom(recipientId)
                .nextLong(minMs, (long) maxMs + 1L);
    }
}
