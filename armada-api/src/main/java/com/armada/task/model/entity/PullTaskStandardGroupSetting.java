package com.armada.task.model.entity;

/** 普通群链接任务期望应用的群资料与权限设置。 */
public class PullTaskStandardGroupSetting {

    private Long id;
    private Long tenantId;
    private Long taskId;
    private Integer settingTiming;
    private String groupName;
    private Integer materialFilenameAsGroupName;
    private String avatarFileKey;
    private String groupDescription;
    private Integer autoUnmuteAfterTask;
    private Integer autoCloseInviteAfterTask;
    private Integer editPermissionMode;
    private Integer muteMode;
    private Integer linkPermissionMode;
    private Integer disappearingMessageMode;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getSettingTiming() { return settingTiming; }
    public void setSettingTiming(Integer value) { this.settingTiming = value; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public Integer getMaterialFilenameAsGroupName() { return materialFilenameAsGroupName; }
    public void setMaterialFilenameAsGroupName(Integer value) {
        this.materialFilenameAsGroupName = value;
    }
    public String getAvatarFileKey() { return avatarFileKey; }
    public void setAvatarFileKey(String avatarFileKey) { this.avatarFileKey = avatarFileKey; }
    public String getGroupDescription() { return groupDescription; }
    public void setGroupDescription(String value) { this.groupDescription = value; }
    public Integer getAutoUnmuteAfterTask() { return autoUnmuteAfterTask; }
    public void setAutoUnmuteAfterTask(Integer value) { this.autoUnmuteAfterTask = value; }
    public Integer getAutoCloseInviteAfterTask() { return autoCloseInviteAfterTask; }
    public void setAutoCloseInviteAfterTask(Integer value) { this.autoCloseInviteAfterTask = value; }
    public Integer getEditPermissionMode() { return editPermissionMode; }
    public void setEditPermissionMode(Integer value) { this.editPermissionMode = value; }
    public Integer getMuteMode() { return muteMode; }
    public void setMuteMode(Integer muteMode) { this.muteMode = muteMode; }
    public Integer getLinkPermissionMode() { return linkPermissionMode; }
    public void setLinkPermissionMode(Integer value) { this.linkPermissionMode = value; }
    public Integer getDisappearingMessageMode() { return disappearingMessageMode; }
    public void setDisappearingMessageMode(Integer value) { this.disappearingMessageMode = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
