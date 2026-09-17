package com.armada.account.model.enums;

/** 手机原生失败类别；本次仅用于记录，不触发补购或供应商订单操作。 */
public enum DeviceRegistrationFailureKind {
    /** 号码级拒绝。 */ NUMBER(1),
    /** 设备或请求限频。 */ RATE_LIMIT(2),
    /** 尚未识别的原生失败。 */ UNKNOWN(3);

    private final int code;
    DeviceRegistrationFailureKind(int code) { this.code = code; }
    /** @return 数据库存储值 */
    public int code() { return code; }
    /** @param code 数据库值，非手机失败时为空 @return 对应类别或空 */
    public static DeviceRegistrationFailureKind fromCode(Integer code) {
        if (code == null) { return null; }
        for (var kind : values()) { if (kind.code == code) { return kind; } }
        throw new IllegalArgumentException("Invalid device failure kind");
    }
}
