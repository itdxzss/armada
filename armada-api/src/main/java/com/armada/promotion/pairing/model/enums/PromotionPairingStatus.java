package com.armada.promotion.pairing.model.enums;

import java.util.Arrays;

/** 推广配对会话状态码。 */
public enum PromotionPairingStatus {
    /** 已创建本地会话，正在等待协议层受理。 */
    REQUESTING(1),

    /** 协议层已生成随机配对码，等待手机确认。 */
    WAITING_CONFIRMATION(2),

    /** 手机已确认，正在导出凭据并写入正式账号。 */
    FINALIZING(3),

    /** 配对完成，正式账号与代理绑定均已落库。 */
    SUCCEEDED(4),

    /** 协议请求或手机确认失败，临时资源已回收。 */
    FAILED(5),

    /** 配对码超过有效期，临时资源已回收。 */
    EXPIRED(6);

    private final int code;

    PromotionPairingStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 按数据库码转换公开状态名。 */
    public static PromotionPairingStatus fromCode(int code) {
        return Arrays.stream(values()).filter(value -> value.code == code).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知配对状态: " + code));
    }
}
