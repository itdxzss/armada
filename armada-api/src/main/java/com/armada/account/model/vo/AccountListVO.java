package com.armada.account.model.vo;

/**
 * 账号列表出参 VO(前端分页列表用此结构)。
 *
 * <p>码值字段(accountType/loginState 等)以 Integer 透传,前端自行映射标签,后端不转中文。</p>
 * <p>时间字段为 Long epoch 毫秒(UTC)。</p>
 * <p>占位字段:avatarUrl/friendsNum/hyperlinkSentCount 恒为 null/0;groupsNum 来自账号当前有效群关系聚合。
 * country/ipSource 来自账号状态或当前绑定 IP 代理行;truthIp 只来自账号状态。</p>
 */
public record AccountListVO(

        // ---- account 主表真值列 ----

        /** 账号主键。 */
        Long id,

        /** WA 号。 */
        String wsPhone,

        /** 当前有效账号类型:1个人 2商业。 */
        Integer accountType,

        /** 导入申报账号类型:1个人 2商业。 */
        Integer declaredAccountType,

        /** 协议校验状态:0待校验 1已匹配 2已纠正 3无法确认 4存量未校验。 */
        Integer accountTypeVerifyStatus,

        /** 协议校验来源:1凭据元数据 2配对结果 3商业资料查询。 */
        Integer accountTypeVerifySource,

        /** 账号类型最后校验时间(epoch 毫秒)。 */
        Long accountTypeVerifiedAt,

        /** 商业认证级别:1蓝标高认证 2明确非高认证；null 未确认。 */
        Integer businessVerificationLevel,

        /** 商业认证识别来源。 */
        Integer businessVerificationSource,

        /** 商业认证级别最后确认时间(epoch 毫秒)。 */
        Long businessVerificationVerifiedAt,

        /** 机型:1安卓 2苹果。 */
        Integer deviceOs,

        /** 来源:1买量 2裂变 3自购。 */
        Integer numberSource,

        /** 推广渠道名。 */
        String channelName,

        /** 接入协议标识。 */
        String protocolId,

        /** 协议后端:WEB/ANDROID。前端上下线时随账号 ID 原样带回。 */
        String protocolBackend,

        /** 归属分组 ID(→account_group.id)。 */
        Long accountGroupId,

        /** 分组名称(LEFT JOIN account_group,分组软删时为 null)。 */
        String groupName,

        /** 营销占用展示类型；值为 FREE、各业务类型、PAUSED 或 RELEASING。 */
        String marketingOccupancyType,

        /** 当前占用营销任务 ID；空闲时为 null。 */
        Long marketingOccupancyTaskId,

        /** 当前营销分组锁定时间(epoch 毫秒)；空闲时为 null。 */
        Long marketingLockedAt,

        /** 归属:1自有 2平台 3租借。 */
        Integer ownership,

        /** 租借到期(epoch 毫秒;ownership=3)。 */
        Long leaseUntil,

        /** 首次派单时间(epoch 毫秒;未分配时为 null)。 */
        Long dispatchedAt,

        /** 入库时间(epoch 毫秒)。 */
        Long createdAt,

        // ---- account_state 状态列(LEFT JOIN,全可空) ----

        /** 账号状态:1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中 8账号受限;NULL=未上报。 */
        Integer accountState,

        /** 登录状态:1在线 2离线;NULL=未上报。 */
        Integer loginState,

        /** 风控状态:1未风控 2风控中 3待解除;NULL=未上报。 */
        Integer riskStatus,

        /** 风控倒计时终点(epoch 毫秒)。 */
        Long riskEndTime,

        /** 账号操作限制统一截止时间(epoch 毫秒)。 */
        Long cooldownUntil,

        /** 操作限制:1消息发送 2拉人 3消息发送和拉人;NULL=未受限。 */
        Integer muteStatus,

        /** 最近一次操作限制原因码。 */
        String restrictionReasonCode,

        /** 最近一次操作限制事实时间(epoch 毫秒)。 */
        Long restrictionReportedAt,

        /** 封号错误码(401/403/440)。 */
        String blockErrorCode,

        /** 封号原因。 */
        String blockReason,

        /** 真实出口公网 IP。 */
        String truthIp,

        /** 拉人数量。 */
        Integer pullIntoGroupCount,

        /** 失效时间(epoch 毫秒;账号状态非正常;恢复正常清空)。 */
        Long invalidatedAt,

        // ---- 占位字段与当前群聚合 ----

        /** 头像 URL(step1 占位,恒 null)。 */
        String avatarUrl,

        /** 好友数(本期占位,恒 0)。 */
        int friendsNum,

        /** 上控后当前有效群组数。 */
        int groupsNum,

        /** 超链发送数(step1 占位,恒 0)。 */
        int hyperlinkSentCount,

        /** 出口国家(状态回写优先,当前绑定代理兜底)。 */
        String country,

        /** 出口国家对应的国旗 emoji;混合国家或无匹配国家时为空。 */
        String countryFlag,

        /** IP 来源(账号状态快照优先,当前绑定 IP 代理来源兜底)。 */
        String ipSource
) {
}
