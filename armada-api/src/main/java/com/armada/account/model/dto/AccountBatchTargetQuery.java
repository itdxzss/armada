package com.armada.account.model.dto;

/**
 * 账号批量操作内部游标查询。
 *
 * <p>继承账号列表筛选字段，仅增加稳定 ID 游标和本轮扫描上限；该对象只在 Service 与 Mapper
 * 之间使用，不暴露为 HTTP 入参。</p>
 */
public class AccountBatchTargetQuery extends AccountQuery {

    private long afterId;
    private int scanSize;

    /**
     * 从列表查询条件创建内部游标查询。
     *
     * @param source   已规范化的列表筛选条件
     * @param afterId  上一轮最后一个账号 ID，首轮传 0
     * @param scanSize 本轮最多扫描账号数
     * @return 独立的内部游标查询对象
     */
    public static AccountBatchTargetQuery from(AccountQuery source, long afterId, int scanSize) {
        AccountBatchTargetQuery target = new AccountBatchTargetQuery();
        target.setKeyword(source.getKeyword());
        target.setPhone(source.getPhone());
        target.setAccountType(source.getAccountType());
        target.setProtocolId(source.getProtocolId());
        target.setAccountState(source.getAccountState());
        target.setRiskStatus(source.getRiskStatus());
        target.setLoginState(source.getLoginState());
        target.setMuteStatus(source.getMuteStatus());
        target.setAccountGroupId(source.getAccountGroupId());
        target.setNumberSource(source.getNumberSource());
        target.setChannelName(source.getChannelName());
        target.setCountry(source.getCountry());
        target.setTruthIp(source.getTruthIp());
        target.afterId = afterId;
        target.scanSize = scanSize;
        return target;
    }

    public long getAfterId() {
        return afterId;
    }

    public int getScanSize() {
        return scanSize;
    }
}
