package com.armada.account.model.vo;

/**
 * 账号列表出参 VO(前端分页列表用此结构)。
 *
 * <p>码值字段(accountType/loginState 等)以 Integer 透传,前端自行映射标签,后端不转中文。</p>
 * <p>时间字段为 Long epoch 毫秒(UTC)。</p>
 * <p>step1 占位字段:avatarUrl/friendsNum/groupsNum/hyperlinkSentCount 恒为 null/0。
 * country/ipSource/truthIp 来自账号状态或当前绑定 IP 代理行。</p>
 */
public record AccountListVO(

        // ---- account 主表真值列 ----

        /** 账号主键。 */
        Long id,

        /** WA 号。 */
        String wsPhone,

        /** 账号类型:1个人 2商业。铁律:导入即冻结不可改写。 */
        Integer accountType,

        /** 机型:1安卓 2苹果。 */
        Integer deviceOs,

        /** 来源:1买量 2裂变 3自购。 */
        Integer numberSource,

        /** 推广渠道名。 */
        String channelName,

        /** 接入协议标识。 */
        String protocolId,

        /** 归属分组 ID(→account_group.id)。 */
        Long accountGroupId,

        /** 分组名称(LEFT JOIN account_group,分组软删时为 null)。 */
        String groupName,

        /** 归属:1自有 2平台 3租借。 */
        Integer ownership,

        /** 租借到期(epoch 毫秒;ownership=3)。 */
        Long leaseUntil,

        /** 首次派单时间(epoch 毫秒;未分配时为 null)。 */
        Long dispatchedAt,

        /** 入库时间(epoch 毫秒)。 */
        Long createdAt,

        // ---- account_state 状态列(LEFT JOIN,全可空) ----

        /** 账号状态:1新增 2正常 3封禁 4导出 5解绑;NULL=未上报。 */
        Integer accountState,

        /** 登录状态:1在线 2离线;NULL=未上报。 */
        Integer loginState,

        /** 风控状态:1未风控 2风控中 3待解除;NULL=未上报。 */
        Integer riskStatus,

        /** 风控倒计时终点(epoch 毫秒)。 */
        Long riskEndTime,

        /** 禁言状态:1禁言6h 2禁言24h;NULL=未上报。 */
        Integer muteStatus,

        /** 封号错误码(401/403/440)。 */
        String blockErrorCode,

        /** 封号原因。 */
        String blockReason,

        /** 真实出口公网 IP。 */
        String truthIp,

        /** 拉人数量。 */
        Integer pullIntoGroupCount,

        /** 失效时间(epoch 毫秒;导出/解绑)。 */
        Long invalidatedAt,

        // ---- step1 占位字段(恒常量,step3 再接真值) ----

        /** 头像 URL(step1 占位,恒 null)。 */
        String avatarUrl,

        /** 好友数(step1 占位,恒 0)。 */
        int friendsNum,

        /** 参与群组数(step1 占位,恒 0)。 */
        int groupsNum,

        /** 超链发送数(step1 占位,恒 0)。 */
        int hyperlinkSentCount,

        /** 出口国家(状态回写优先,当前绑定代理兜底)。 */
        String country,

        /** IP 来源(当前绑定 IP 代理来源)。 */
        String ipSource
) {
}
