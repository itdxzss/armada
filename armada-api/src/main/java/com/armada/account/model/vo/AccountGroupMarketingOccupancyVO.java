package com.armada.account.model.vo;

/**
 * 账号分组营销整组占用详情。
 *
 * @param groupId 分组 ID
 * @param occupancyType 前端展示类型 key
 * @param taskBusinessType 统一营销任务业务类型；锁归属任务缺失时为空
 * @param taskId 当前占用任务 ID；空闲时为空
 * @param taskName 当前占用任务名称；锁归属任务缺失时为空
 * @param taskStatus 当前占用任务主状态；锁归属任务缺失时为空
 * @param resourceStatus 拉群营销资源状态；普通营销或任务缺失时为空
 * @param lockedAt 分组锁定时间(epoch 毫秒)
 * @param marketingAccountTotalCount 锁定或选中的营销账号总数
 * @param marketingAccountUsedCount 当前任务实际调用的营销账号数
 */
public record AccountGroupMarketingOccupancyVO(
        Long groupId,
        String occupancyType,
        Integer taskBusinessType,
        Long taskId,
        String taskName,
        Integer taskStatus,
        Integer resourceStatus,
        Long lockedAt,
        int marketingAccountTotalCount,
        int marketingAccountUsedCount
) {
}
