package com.armada.resource.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 拉群数据包当前号码状态，执行历史仍由任务保存。 */
public enum GroupDataPackagePhoneStatus {
    /** 可供分配。 */ UNUSED(1),
    /** 已由任务冻结，尚无明确结果。 */ CLAIMED(2),
    /** 已确认入群。 */ SUCCESS(3),
    /** 明确失败，可人工回收。 */ RETRYABLE_FAILED(4),
    /** 隐私设置拒绝拉群。 */ PRIVACY_REJECTED(5),
    /** 明确未注册。 */ UNREGISTERED(6),
    /** 已提交且结果待确认，不可自动回收。 */ UNKNOWN(7);
    private final int code;
    GroupDataPackagePhoneStatus(int code) { this.code = code; }
    /** 数据库存储值。 */ public int code() { return code; }
    /** 校验数据库状态值。 */
    public static GroupDataPackagePhoneStatus of(int code) {
        for (var value : values()) { if (value.code == code) { return value; } }
        throw new BusinessException(ErrorCode.VALIDATION, "号码状态不合法");
    }
    /** 校验接口状态名。 */
    public static GroupDataPackagePhoneStatus parse(String value) {
        try { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "号码状态不合法");
        }
    }
}
