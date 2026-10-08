package com.armada.task.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 拉群任务中建群人、拉手及管理员离线时的等待配置。 */
@ConfigurationProperties(prefix = "armada.task.offline-role-wait")
public class PullTaskOfflineRoleWaitProperties {

    /** 任务侧开关；关闭时保持原有角色离线处理行为。 */
    private boolean enabled = true;

    /** 拉手、管理员等可替换角色离线后的宽限时长，单位毫秒。 */
    private long replaceableGraceMs = 30_000L;

    /** 新群建群人离线后的宽限时长，单位毫秒。 */
    private long creatorGraceMs = 180_000L;

    /** @return 是否启用任务侧角色离线等待行为 */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled 是否启用任务侧角色离线等待行为 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return 可替换角色离线后的宽限时长，单位毫秒 */
    public long getReplaceableGraceMs() {
        return replaceableGraceMs;
    }

    /**
     * 配置拉手、管理员离线后保留原角色的宽限时长。
     * @param replaceableGraceMs 可替换角色的宽限时长，单位毫秒
     * @throws IllegalArgumentException 当时长小于等于 0 时抛出
     */
    public void setReplaceableGraceMs(long replaceableGraceMs) {
        if (replaceableGraceMs <= 0) {
            throw new IllegalArgumentException("可替换角色离线宽限时长必须大于 0");
        }
        this.replaceableGraceMs = replaceableGraceMs;
    }

    /** @return 新群建群人离线后的宽限时长，单位毫秒 */
    public long getCreatorGraceMs() {
        return creatorGraceMs;
    }

    /**
     * 配置不可替换的新群建群人的离线宽限时长。
     * @param creatorGraceMs 建群人离线宽限时长，单位毫秒
     * @throws IllegalArgumentException 当时长小于等于 0 时抛出
     */
    public void setCreatorGraceMs(long creatorGraceMs) {
        if (creatorGraceMs <= 0) {
            throw new IllegalArgumentException("建群人离线宽限时长必须大于 0");
        }
        this.creatorGraceMs = creatorGraceMs;
    }
}
