package com.armada.task.model.entity;

/** 拉群营销任务列表聚合实体，映射 {@code pull_task_group_marketing_summary}。 */
public class PullTaskGroupMarketingSummary {

    /** 租户 ID。 */
    private Long tenantId;
    /** 拉群任务 ID。 */
    private Long taskId;
    /** 目标群组数。 */
    private int targetGroupCount;
    /** 转移成功群组数。 */
    private int transferSuccessCount;
    /** 转移待收口群组数。 */
    private int transferPendingCloseCount;
    /** 转移部分完成群组数。 */
    private int transferPartialCount;
    /** 转移失败群组数。 */
    private int transferFailedCount;
    /** 转移执行中群组数。 */
    private int transferRunningCount;
    /** 转移等待执行群组数。 */
    private int transferWaitingCount;
    /** 计划目标人数。 */
    private int plannedTargetCount;
    /** 有效目标人数。 */
    private int effectiveTargetCount;
    /** 新增成功人数。 */
    private int joinedSuccessCount;
    /** 已在群内人数。 */
    private int alreadyInGroupCount;
    /** 隐私限制人数。 */
    private int privacyRestrictedCount;
    /** 无效号码数。 */
    private int invalidNumberCount;
    /** 未注册号码数。 */
    private int unregisteredCount;
    /** 拉人结果未知数。 */
    private int pullResultUnknownCount;
    /** 剩余有效目标人数。 */
    private int remainingTargetCount;
    /** 营销待开始群组数。 */
    private int marketingWaitingCount;
    /** 营销进行中群组数。 */
    private int marketingRunningCount;
    /** 营销已暂停群组数。 */
    private int marketingPausedCount;
    /** 营销已完成群组数。 */
    private int marketingCompletedCount;
    /** 营销异常停止群组数。 */
    private int marketingAbnormalStoppedCount;
    /** 最终发送成功消息数。 */
    private int messageSuccessCount;
    /** 最终发送失败消息数。 */
    private int messageFailedCount;
    /** 发送结果未知消息数。 */
    private int messageUnknownCount;
    /** 去重异常群组数。 */
    private int abnormalGroupCount;
    /** 缺少拉手群组数。 */
    private int pullerShortageGroupCount;
    /** 去重封禁账号数。 */
    private int bannedAccountCount;
    /** 当前可用拉手数。 */
    private int availablePullerCount;
    /** 目标数据是否不足。 */
    private boolean targetDataShortage;
    /** 拉手是否不足。 */
    private boolean pullerShortage;
    /** 水军是否不足。 */
    private boolean waterArmyShortage;
    /** 管理员是否不足。 */
    private boolean adminShortage;
    /** 营销账号是否不足。 */
    private boolean marketingAdminShortage;
    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;
    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public int getTargetGroupCount() {
        return targetGroupCount;
    }

    public void setTargetGroupCount(int targetGroupCount) {
        this.targetGroupCount = targetGroupCount;
    }

    public int getTransferSuccessCount() {
        return transferSuccessCount;
    }

    public void setTransferSuccessCount(int transferSuccessCount) {
        this.transferSuccessCount = transferSuccessCount;
    }

    public int getTransferPendingCloseCount() {
        return transferPendingCloseCount;
    }

    public void setTransferPendingCloseCount(int transferPendingCloseCount) {
        this.transferPendingCloseCount = transferPendingCloseCount;
    }

    public int getTransferPartialCount() {
        return transferPartialCount;
    }

    public void setTransferPartialCount(int transferPartialCount) {
        this.transferPartialCount = transferPartialCount;
    }

    public int getTransferFailedCount() {
        return transferFailedCount;
    }

    public void setTransferFailedCount(int transferFailedCount) {
        this.transferFailedCount = transferFailedCount;
    }

    public int getTransferRunningCount() {
        return transferRunningCount;
    }

    public void setTransferRunningCount(int transferRunningCount) {
        this.transferRunningCount = transferRunningCount;
    }

    public int getTransferWaitingCount() {
        return transferWaitingCount;
    }

    public void setTransferWaitingCount(int transferWaitingCount) {
        this.transferWaitingCount = transferWaitingCount;
    }

    public int getPlannedTargetCount() {
        return plannedTargetCount;
    }

    public void setPlannedTargetCount(int plannedTargetCount) {
        this.plannedTargetCount = plannedTargetCount;
    }

    public int getEffectiveTargetCount() {
        return effectiveTargetCount;
    }

    public void setEffectiveTargetCount(int effectiveTargetCount) {
        this.effectiveTargetCount = effectiveTargetCount;
    }

    public int getJoinedSuccessCount() {
        return joinedSuccessCount;
    }

    public void setJoinedSuccessCount(int joinedSuccessCount) {
        this.joinedSuccessCount = joinedSuccessCount;
    }

    public int getAlreadyInGroupCount() {
        return alreadyInGroupCount;
    }

    public void setAlreadyInGroupCount(int alreadyInGroupCount) {
        this.alreadyInGroupCount = alreadyInGroupCount;
    }

    public int getPrivacyRestrictedCount() {
        return privacyRestrictedCount;
    }

    public void setPrivacyRestrictedCount(int privacyRestrictedCount) {
        this.privacyRestrictedCount = privacyRestrictedCount;
    }

    public int getInvalidNumberCount() {
        return invalidNumberCount;
    }

    public void setInvalidNumberCount(int invalidNumberCount) {
        this.invalidNumberCount = invalidNumberCount;
    }

    public int getUnregisteredCount() {
        return unregisteredCount;
    }

    public void setUnregisteredCount(int unregisteredCount) {
        this.unregisteredCount = unregisteredCount;
    }

    public int getPullResultUnknownCount() {
        return pullResultUnknownCount;
    }

    public void setPullResultUnknownCount(int pullResultUnknownCount) {
        this.pullResultUnknownCount = pullResultUnknownCount;
    }

    public int getRemainingTargetCount() {
        return remainingTargetCount;
    }

    public void setRemainingTargetCount(int remainingTargetCount) {
        this.remainingTargetCount = remainingTargetCount;
    }

    public int getMarketingWaitingCount() {
        return marketingWaitingCount;
    }

    public void setMarketingWaitingCount(int marketingWaitingCount) {
        this.marketingWaitingCount = marketingWaitingCount;
    }

    public int getMarketingRunningCount() {
        return marketingRunningCount;
    }

    public void setMarketingRunningCount(int marketingRunningCount) {
        this.marketingRunningCount = marketingRunningCount;
    }

    public int getMarketingPausedCount() {
        return marketingPausedCount;
    }

    public void setMarketingPausedCount(int marketingPausedCount) {
        this.marketingPausedCount = marketingPausedCount;
    }

    public int getMarketingCompletedCount() {
        return marketingCompletedCount;
    }

    public void setMarketingCompletedCount(int marketingCompletedCount) {
        this.marketingCompletedCount = marketingCompletedCount;
    }

    public int getMarketingAbnormalStoppedCount() {
        return marketingAbnormalStoppedCount;
    }

    public void setMarketingAbnormalStoppedCount(int marketingAbnormalStoppedCount) {
        this.marketingAbnormalStoppedCount = marketingAbnormalStoppedCount;
    }

    public int getMessageSuccessCount() {
        return messageSuccessCount;
    }

    public void setMessageSuccessCount(int messageSuccessCount) {
        this.messageSuccessCount = messageSuccessCount;
    }

    public int getMessageFailedCount() {
        return messageFailedCount;
    }

    public void setMessageFailedCount(int messageFailedCount) {
        this.messageFailedCount = messageFailedCount;
    }

    public int getMessageUnknownCount() {
        return messageUnknownCount;
    }

    public void setMessageUnknownCount(int messageUnknownCount) {
        this.messageUnknownCount = messageUnknownCount;
    }

    public int getAbnormalGroupCount() {
        return abnormalGroupCount;
    }

    public void setAbnormalGroupCount(int abnormalGroupCount) {
        this.abnormalGroupCount = abnormalGroupCount;
    }

    public int getPullerShortageGroupCount() {
        return pullerShortageGroupCount;
    }

    public void setPullerShortageGroupCount(int pullerShortageGroupCount) {
        this.pullerShortageGroupCount = pullerShortageGroupCount;
    }

    public int getBannedAccountCount() {
        return bannedAccountCount;
    }

    public void setBannedAccountCount(int bannedAccountCount) {
        this.bannedAccountCount = bannedAccountCount;
    }

    public int getAvailablePullerCount() {
        return availablePullerCount;
    }

    public void setAvailablePullerCount(int availablePullerCount) {
        this.availablePullerCount = availablePullerCount;
    }

    public boolean isTargetDataShortage() {
        return targetDataShortage;
    }

    public void setTargetDataShortage(boolean targetDataShortage) {
        this.targetDataShortage = targetDataShortage;
    }

    public boolean isPullerShortage() {
        return pullerShortage;
    }

    public void setPullerShortage(boolean pullerShortage) {
        this.pullerShortage = pullerShortage;
    }

    public boolean isWaterArmyShortage() {
        return waterArmyShortage;
    }

    public void setWaterArmyShortage(boolean waterArmyShortage) {
        this.waterArmyShortage = waterArmyShortage;
    }

    public boolean isAdminShortage() {
        return adminShortage;
    }

    public void setAdminShortage(boolean adminShortage) {
        this.adminShortage = adminShortage;
    }

    public boolean isMarketingAdminShortage() {
        return marketingAdminShortage;
    }

    public void setMarketingAdminShortage(boolean marketingAdminShortage) {
        this.marketingAdminShortage = marketingAdminShortage;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
