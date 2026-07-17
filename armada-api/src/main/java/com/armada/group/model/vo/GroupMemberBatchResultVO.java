package com.armada.group.model.vo;

import java.util.List;

/**
 * 群成员批量操作汇总。
 *
 * @param ok      是否全部成功
 * @param partial 是否存在未成功或待确认成员
 * @param message 汇总提示
 * @param results 按请求顺序返回的逐成员结果
 */
public record GroupMemberBatchResultVO(
        boolean ok,
        boolean partial,
        String message,
        List<GroupMemberOperationResultVO> results) {
}
