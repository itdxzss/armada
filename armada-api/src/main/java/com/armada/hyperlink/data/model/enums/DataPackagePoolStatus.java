package com.armada.hyperlink.data.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 数据包号码当前资源池状态。 */
public enum DataPackagePoolStatus {

    /** 尚未被任务领取。 */
    UNUSED(1),

    /** 已被任务原子领取。 */
    CLAIMED(2),

    /** 已发送且当前停留在单钩。 */
    SENT(3),

    /** 已收到送达回执。 */
    DELIVERED(4),

    /** 发送失败但未来允许重试。 */
    RETRYABLE_FAILED(5),

    /** 已确认未注册 WhatsApp。 */
    UNREGISTERED(6);

    private final int code;

    DataPackagePoolStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 把 API 枚举名解析为状态；空值表示不筛选。 */
    public static DataPackagePoolStatus optionalFromApi(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        for (DataPackagePoolStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "号码池状态不合法");
    }

    /** 按数据库码还原 API 枚举。 */
    public static DataPackagePoolStatus fromCode(Integer code) {
        if (code != null) {
            for (DataPackagePoolStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("未知数据包号码池状态: " + code);
    }
}
