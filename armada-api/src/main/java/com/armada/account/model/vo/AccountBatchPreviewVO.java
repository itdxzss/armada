package com.armada.account.model.vo;

import java.util.Map;

/**
 * 账号批量操作执行前预估。
 *
 * @param matched     当前范围匹配账号数
 * @param executable  预计进入协议命令的账号数
 * @param skipped     预计被 Armada 跳过的账号数
 * @param skipReasons 按稳定原因 key 聚合的跳过数量
 */
public record AccountBatchPreviewVO(
        long matched,
        long executable,
        long skipped,
        Map<String, Long> skipReasons
) {
}
