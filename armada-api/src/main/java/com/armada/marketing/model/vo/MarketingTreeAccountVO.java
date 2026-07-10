package com.armada.marketing.model.vo;

import java.util.List;

/**
 * 营销账号树的账号节点。
 *
 * @param accountId      账号 ID
 * @param wsPhone        WhatsApp 号码
 * @param status         账号机器状态码,用于前端判断是否可选
 * @param statusText     账号中文展示状态
 * @param groupCount     当前库内可营销群数量
 * @param selectable     当前账号是否允许作为营销任务目标
 * @param disabledReason 不可选原因;可选账号为空
 * @param groupsError    是否获取群失败;DB 查询失败时为 true
 * @param groups         该账号可选择的营销群
 */
public record MarketingTreeAccountVO(
        Long accountId,
        String wsPhone,
        String status,
        String statusText,
        Integer groupCount,
        Boolean selectable,
        String disabledReason,
        Boolean groupsError,
        List<MarketingTreeGroupVO> groups) {
}
