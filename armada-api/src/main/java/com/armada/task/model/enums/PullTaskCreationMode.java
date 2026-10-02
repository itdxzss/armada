package com.armada.task.model.enums;

/**
 * 拉群任务的新建模式；与 {@code pull_task.creation_mode} 一一对应。
 *
 * <p>它表达群来源及创建流程：DIRECT_LINK 使用链接来源并省略管理链。
 * {@code pull_task.mode=NORMAL_LINK} 仍表示共享普通拉群调度族。
 * 另请注意与同表 {@code group_source} 区分：
 * 那是拉群营销的历史群/自收群来源，语义无关。</p>
 */
public enum PullTaskCreationMode {

    /** 群链接模式：群链接由用户粘贴或从群组分组选择。 */
    PASTED_LINK,
    /** 群链接模式（新）：普通成员拉手直接入群并拉人，不使用管理链。 */
    DIRECT_LINK,
    /** 资源池模式：任务选择群组分组，执行时动态领取群组。 */
    RESOURCE_POOL,
    /** 新群模式：由建群人现场创建群，建群成功后回填链接。 */
    NEW_GROUP;

    /**
     * 兼容不传该字段的既有前端与存量草稿。
     *
     * @param value 可能为空的模式
     * @return 为空时返回群链接模式
     */
    public static PullTaskCreationMode fromNullable(PullTaskCreationMode value) {
        return value == null ? PASTED_LINK : value;
    }

    /** @return 是否为新群模式 */
    public boolean isNewGroup() {
        return this == NEW_GROUP;
    }

    /** @return 是否为运行时动态取群的资源池模式 */
    public boolean isResourcePool() {
        return this == RESOURCE_POOL;
    }

    /** @return 是否采用普通成员拉手直接入群流程 */
    public boolean isDirectLink() {
        return this == DIRECT_LINK;
    }

    /** @return 是否以分组或手工链接作为群来源 */
    public boolean isLinkMode() {
        return this == PASTED_LINK || this == DIRECT_LINK;
    }

    /**
     * 判断普通任务是否由已选择的群组分组提供群组。
     *
     * <p>资源池存量任务按原语义始终走分组；群链接模式只有实际保存来源分组时，才允许成功移组和
     * 封群后领取下一个群。纯手工粘贴链接不能据此自动换群。</p>
     *
     * @param sourceGroupFolderId 冻结配置中的来源群组分组 ID
     * @return 是否使用已选择的群组分组
     */
    public boolean usesSelectedGroupFolder(Long sourceGroupFolderId) {
        return this == RESOURCE_POOL || (isLinkMode() && sourceGroupFolderId != null);
    }
}
