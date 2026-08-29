package com.armada.account.service;

/** 账号域接收可信画像事实的最小写入边界。 */
public interface AccountProfileService {

    /**
     * 写入好友数事实。
     *
     * @param accountId 当前租户账号 ID
     * @param friendCount 非负双向好友数
     * @param syncedAt 事实同步时间(epoch 毫秒)
     */
    void updateFriendCount(long accountId, int friendCount, long syncedAt);

    /**
     * 写入是否允许被拉群的隐私事实。
     *
     * @param accountId 当前租户账号 ID
     * @param allowed 是否允许被拉群
     * @param syncedAt 事实同步时间(epoch 毫秒)
     */
    void updateGroupInviteAllowed(long accountId, boolean allowed, long syncedAt);

    /**
     * 写入轮号状态事实。
     *
     * @param accountId 当前租户账号 ID
     * @param status 0未轮号、1轮号中、2成功、3失败
     * @param updatedAt 轮号事实时间(epoch 毫秒)
     */
    void updateRotationStatus(long accountId, int status, long updatedAt);

    /**
     * 初始化静态注册时间事实；已有可信值时保持原值。
     *
     * @param accountId 当前租户账号 ID
     * @param registeredAt WhatsApp 估算注册时间(epoch 毫秒)
     * @param source 1供应商准确日期、2供应商号龄反推、3人工维护
     */
    void initializeRegistration(long accountId, long registeredAt, int source);

    /**
     * 写入五类营销来源事实。
     *
     * @param accountId 当前租户账号 ID
     * @param source 0买量、1自登、2买入、3转入、4群扫码
     * @param updatedAt 来源事实时间(epoch 毫秒)
     */
    void updateMarketingSource(long accountId, int source, long updatedAt);
}
