package com.armada.marketing.model.vo;

import java.util.List;

/**
 * 营销账号树的账号节点。
 *
 * @param accountId   账号 ID
 * @param wsPhone     WhatsApp 号码
 * @param status      账号展示状态;当前接口只返回 ONLINE
 * @param groupsError 是否获取群失败;当前阶段只读本地库,固定 false
 * @param groups      该账号可选择的营销群
 */
public record MarketingTreeAccountVO(
        Long accountId,
        String wsPhone,
        String status,
        Boolean groupsError,
        List<MarketingTreeGroupVO> groups) {
}
