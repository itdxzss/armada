package com.armada.account.model.vo;

import java.util.List;

/**
 * 批量新聊天额度查询结果。
 *
 * @param requested 去重后的请求账号数
 * @param succeeded queryStatus 为 SUCCESS 的账号数
 * @param failed 其余账号数（含平台拒绝、离线、不支持、不可用和失败）
 * @param items 按请求顺序排列的逐账号结果
 */
public record AccountMessageCapBatchVO(
        int requested,
        int succeeded,
        int failed,
        List<AccountMessageCapItemVO> items) {
}
