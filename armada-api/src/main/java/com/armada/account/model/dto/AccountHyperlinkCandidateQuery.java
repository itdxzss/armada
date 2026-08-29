package com.armada.account.model.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 超链任务选择发信账号的账号域查询条件。
 *
 * <p>该对象只在超链任务域与账号域之间传递，不直接绑定 HTTP 请求。所有用户筛选均已由任务域
 * 完成白名单校验和归一化；账号域在同一条 SQL 中关联身份、状态、凭据、导入批次和账号画像，
 * 保证页面试算、正式选号使用完全相同的条件。</p>
 *
 * <p>基础候选固定要求账号有效、未禁言、手机号和协议身份完整，并且协议后端已通过 PRIVATE
 * 能力门禁。除排除国家外，画像字段为 {@code NULL} 时只在未配置对应筛选的情况下入选，禁止把
 * 未采集事实当作 {@code 0}、{@code false} 或其他默认值。</p>
 */
public record AccountHyperlinkCandidateQuery(
        /** 包含国家，两位大写 ISO 3166-1 alpha-2 代码；空列表表示不限。 */
        List<String> countryIso2s,
        /** 排除国家，两位大写 ISO 代码；国家未知的账号不因排除条件被误删。 */
        List<String> excludeCountryIso2s,
        /** 大洲代码；支持七大洲稳定枚举，按号码解析出的国家归属筛选。 */
        String continent,
        /** 账号分组 ID 集合，对应 {@code account.account_group_id}；空列表表示不限。 */
        List<Long> groupIds,
        /** 推广渠道 ID 集合，对应 {@code account.promotion_channel_id}；空列表表示不限。 */
        List<Long> channelIds,
        /** 接入协议标识，已转为大写，按去空格后的值精确匹配。 */
        String protocolId,
        /** 在线状态：{@code ONLINE} 对应登录态 1，{@code OFFLINE} 对应登录态 2。 */
        String onlineStatus,
        /** 轮号状态：0 未轮号、1 轮号中、2 成功、3 失败；未知画像不命中。 */
        Integer rotationStatus,
        /** 账号类型：1 个人、2 商业。 */
        Integer accountType,
        /** 设备平台六值，由设备系统、账号类型和协议后端组合判断，不单独落库。 */
        String platform,
        /** 设备接入类型：{@code web5} 分身设备、{@code native6} 主设备。 */
        String widType,
        /** 凭据导入方式：{@code six_segment} 六段、{@code full_param} 全参数。 */
        String importMode,
        /** 是否允许被拉群；配置后只匹配已采集且值相同的账号画像。 */
        Boolean groupInviteAllowed,
        /** WhatsApp 手机号片段，去除首尾空白后进行包含匹配。 */
        String phone,
        /** 账号导入批次 ID，通过导入明细中的账号关联筛选。 */
        Long importBatchId,
        /** 运营来源：0 买量、1 自登、2 买入、3 转入、4 群扫码。 */
        Integer source,
        /** 双向好友数下限，闭区间；用户输入 0 已在归一化阶段转为不限。 */
        Integer friendCountMin,
        /** 双向好友数上限，闭区间；用户输入 0 已在归一化阶段转为不限。 */
        Integer friendCountMax,
        /** 存活天数下限，最多一位小数，由观察时刻减账号入库时间计算。 */
        BigDecimal retentionDaysMin,
        /** 存活天数上限，最多一位小数，由观察时刻减账号入库时间计算。 */
        BigDecimal retentionDaysMax,
        /** WhatsApp 注册天数下限，按完整自然天向下取整；注册时间未知时不命中。 */
        Integer registerDaysMin,
        /** WhatsApp 注册天数上限，按完整自然天向下取整；注册时间未知时不命中。 */
        Integer registerDaysMax,
        /** 账号入库时间下界，epoch 毫秒，包含该时刻。 */
        Long createdAtFrom,
        /** 账号入库时间上界，epoch 毫秒，不包含该时刻。 */
        Long createdAtTo,
        /** 已通过 PRIVATE 真机能力门禁的协议后端，只允许 {@code WEB}/{@code ANDROID}。 */
        List<String> privateCapableBackends,
        /** 本次试算或选号冻结的观察时刻，epoch 毫秒，统一计算存活和注册天数。 */
        long observedAt) {
}
