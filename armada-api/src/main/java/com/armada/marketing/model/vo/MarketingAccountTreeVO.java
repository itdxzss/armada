package com.armada.marketing.model.vo;

import java.util.List;

/**
 * 建营销任务抽屉的账号→可营销群树。
 *
 * @param accounts 在线可用账号列表
 */
public record MarketingAccountTreeVO(List<MarketingTreeAccountVO> accounts) {
}
