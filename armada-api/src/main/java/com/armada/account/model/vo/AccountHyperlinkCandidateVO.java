package com.armada.account.model.vo;

/**
 * 账号域提供给超链任务的最小候选快照。
 *
 * <p>任务域只消费该只读投影，不依赖账号表实体或账号 Mapper。</p>
 *
 * @param accountId 账号主键
 * @param priority 账号选号优先级，用于稳定游标分页
 * @param wsPhone 发信号码
 * @param countryIso2 由号码最长区号匹配得到的国家代码；无法识别时为空
 * @param accountType 账号类型
 * @param deviceOs 设备 OS:1 安卓,2 苹果
 * @param createdAt 账号入库时间
 * @param protocolId 协议标识
 * @param protocolAccountId 协议账号句柄
 * @param protocolBackend 由凭据格式优先、协议标识兜底派生的 WEB/ANDROID
 */
public record AccountHyperlinkCandidateVO(
        long accountId,
        int priority,
        String wsPhone,
        String countryIso2,
        int accountType,
        Integer deviceOs,
        long createdAt,
        String protocolId,
        String protocolAccountId,
        String protocolBackend) {
}
