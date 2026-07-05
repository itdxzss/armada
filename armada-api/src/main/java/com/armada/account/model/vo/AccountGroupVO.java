package com.armada.account.model.vo;

/**
 * 账号分组出参 VO(前端列表/详情均用此结构)。
 *
 * @param id           分组 ID
 * @param name         分组名称
 * @param remark       备注
 * @param systemBuiltin 是否系统内置:1=是,0=否
 * @param accountCount  分组下账号总数
 * @param onlineCount   在线账号数(login_state=1)
 * @param riskCount     风控账号数(risk_status&gt;1)
 * @param restrictedCount 异常账号总计(banned + unbound + muted + exported)
 * @param bannedCount   封禁账号数(account_state=3)
 * @param createdAt    创建时间(epoch 毫秒,UTC)
 * @param updatedAt    更新时间(epoch 毫秒,UTC)
 */
public record AccountGroupVO(
        Long id,
        String name,
        String remark,
        Integer systemBuiltin,
        long accountCount,
        long onlineCount,
        long riskCount,
        long restrictedCount,
        long bannedCount,
        Long createdAt,
        Long updatedAt
) {
}
