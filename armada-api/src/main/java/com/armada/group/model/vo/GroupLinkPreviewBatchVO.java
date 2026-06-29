package com.armada.group.model.vo;

import java.util.List;

/**
 * 批量实时预览汇总。
 *
 * @param total     请求预览数量
 * @param succeeded 成功数量
 * @param failed    失败数量
 * @param items     单条结果
 */
public record GroupLinkPreviewBatchVO(
        int total,
        int succeeded,
        int failed,
        List<GroupLinkPreviewItemVO> items) {
}
