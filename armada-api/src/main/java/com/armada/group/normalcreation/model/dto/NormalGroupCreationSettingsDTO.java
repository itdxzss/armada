package com.armada.group.normalcreation.model.dto;

/** 新建普群的五项明确权限。 */
public record NormalGroupCreationSettingsDTO(
        Boolean sendMessagesAllowed,
        Boolean editGroupSettingsAllowed,
        Boolean addMembersAllowed,
        Boolean joinApprovalEnabled,
        Integer ephemeralDurationSeconds) {

    /** 返回产品确认的默认权限。 */
    public static NormalGroupCreationSettingsDTO defaults() {
        return new NormalGroupCreationSettingsDTO(true, false, true, false, 0);
    }

    /** 用明确默认值补齐前端未传的字段。 */
    public NormalGroupCreationSettingsDTO normalized() {
        NormalGroupCreationSettingsDTO defaults = defaults();
        return new NormalGroupCreationSettingsDTO(
                sendMessagesAllowed == null ? defaults.sendMessagesAllowed : sendMessagesAllowed,
                editGroupSettingsAllowed == null
                        ? defaults.editGroupSettingsAllowed : editGroupSettingsAllowed,
                addMembersAllowed == null ? defaults.addMembersAllowed : addMembersAllowed,
                joinApprovalEnabled == null ? defaults.joinApprovalEnabled : joinApprovalEnabled,
                ephemeralDurationSeconds == null
                        ? defaults.ephemeralDurationSeconds : ephemeralDurationSeconds);
    }
}
