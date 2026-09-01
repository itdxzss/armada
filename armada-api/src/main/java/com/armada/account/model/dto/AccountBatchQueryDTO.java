package com.armada.account.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 按账号列表筛选条件执行批量操作的请求体。
 *
 * <p>本 DTO 故意不包含分页字段。空对象表示当前租户全部未软删账号；字符串在进入
 * MyBatis 查询前统一去除首尾空白，避免列表与批量操作产生筛选口径差异。</p>
 *
 * @param keyword        账号前缀或备注关键字
 * @param phone          手机号前缀
 * @param accountType    账号类型
 * @param protocolId     接入协议标识
 * @param accountState   账号生命周期状态
 * @param riskStatus     风控状态
 * @param loginState     登录状态
 * @param muteStatus     操作限制状态
 * @param accountGroupId 账号分组 ID
 * @param numberSource   号码来源
 * @param channelName    推广渠道名
 * @param country        IP 国家或出口国家
 * @param truthIp        真实出口 IP
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountBatchQueryDTO(
        String keyword,
        String phone,
        Integer accountType,
        String protocolId,
        Integer accountState,
        Integer riskStatus,
        Integer loginState,
        Integer muteStatus,
        Long accountGroupId,
        Integer numberSource,
        String channelName,
        String country,
        String truthIp
) {

    /**
     * 转换为账号列表已经使用的 SQL 查询对象。
     *
     * <p>分页保持 {@link AccountQuery} 默认值，但批量目标查询不会读取这些分页字段。</p>
     *
     * @return 具有相同筛选语义的账号查询对象
     */
    public AccountQuery toAccountQuery() {
        AccountQuery query = new AccountQuery();
        query.setKeyword(trimToNull(keyword));
        query.setPhone(trimToNull(phone));
        query.setAccountType(accountType);
        query.setProtocolId(trimToNull(protocolId));
        query.setAccountState(accountState);
        query.setRiskStatus(riskStatus);
        query.setLoginState(loginState);
        query.setMuteStatus(muteStatus);
        query.setAccountGroupId(accountGroupId);
        query.setNumberSource(numberSource);
        query.setChannelName(trimToNull(channelName));
        query.setCountry(trimToNull(country));
        query.setTruthIp(trimToNull(truthIp));
        return query;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
