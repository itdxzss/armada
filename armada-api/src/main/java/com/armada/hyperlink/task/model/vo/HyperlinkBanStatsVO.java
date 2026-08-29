package com.armada.hyperlink.task.model.vo;

import java.util.List;

/** 任务封号原因分布。 */
public record HyperlinkBanStatsVO(long invalidAccountCount, List<Item> stats) {
    public record Item(String reason, String note, long count, double percentage) { }
}
