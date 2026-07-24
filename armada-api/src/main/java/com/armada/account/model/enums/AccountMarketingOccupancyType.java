package com.armada.account.model.enums;

/**
 * 账号分组营销占用展示类型。
 *
 * <p>带持久化编码的枚举值对应 {@code account_group.marketing_occupancy_type}；
 * 空闲、暂停占用和待释放是账号菜单的派生展示状态，不写入该字段。</p>
 */
public enum AccountMarketingOccupancyType {

    /** 分组当前未被营销任务占用。 */
    FREE(null),

    /** 单纯营销任务占用。 */
    SIMPLE_MARKETING(1),

    /** 拉群营销任务占用。 */
    GROUP_PULL_MARKETING(2),

    /** 拉群模式二任务占用。 */
    GROUP_PULL_MODE_2(3),

    /** 拉群模式三任务占用。 */
    GROUP_PULL_MODE_3(4),

    /** 其他营销任务占用或无法识别的已占用类型。 */
    OTHER_MARKETING(5),

    /** 占用任务已暂停。 */
    PAUSED(null),

    /** 占用任务正在安全释放资源。 */
    RELEASING(null);

    /** 账号分组表持久化编码；派生展示状态为空。 */
    private final Integer occupancyCode;

    /**
     * 创建账号分组营销占用类型。
     *
     * @param occupancyCode 账号分组表持久化编码
     */
    AccountMarketingOccupancyType(Integer occupancyCode) {
        this.occupancyCode = occupancyCode;
    }

    /**
     * 根据分组锁事实和 Mapper 派生状态解析最终展示类型。
     *
     * <p>待释放优先于暂停，两者均优先于持久化业务类型。任务 ID 为空时固定展示为空闲。</p>
     *
     * @param taskId 当前占用任务 ID
     * @param occupancyCode 分组持久化营销占用编码
     * @param overrideType Mapper 根据任务状态派生的覆盖展示类型
     * @return 账号菜单最终展示的营销占用类型
     */
    public static AccountMarketingOccupancyType resolve(Long taskId,
                                                        Integer occupancyCode,
                                                        String overrideType) {
        if (taskId == null) {
            return FREE;
        }
        if (RELEASING.name().equals(overrideType)) {
            return RELEASING;
        }
        if (PAUSED.name().equals(overrideType)) {
            return PAUSED;
        }
        for (AccountMarketingOccupancyType type : values()) {
            if (type.occupancyCode != null && type.occupancyCode.equals(occupancyCode)) {
                return type;
            }
        }
        return OTHER_MARKETING;
    }
}
