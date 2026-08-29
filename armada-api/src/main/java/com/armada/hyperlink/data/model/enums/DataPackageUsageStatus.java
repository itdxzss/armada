package com.armada.hyperlink.data.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;

/** 竞品数据包导出的号码使用状态口径。 */
public enum DataPackageUsageStatus {

    /** 当前代全部号码。 */
    ALL("all", List.of()),

    /** 尚未被任务领取的号码。 */
    UNUSED("unused", List.of(DataPackagePoolStatus.UNUSED.code())),

    /** 已发送成功，包含单钩与双钩。 */
    SUCCESS("success", List.of(
            DataPackagePoolStatus.SENT.code(), DataPackagePoolStatus.DELIVERED.code())),

    /** 已发送但尚未收到送达回执的单钩号码。 */
    SINGLE("single", List.of(DataPackagePoolStatus.SENT.code())),

    /** 已收到送达回执的双钩号码。 */
    DOUBLE("double", List.of(DataPackagePoolStatus.DELIVERED.code())),

    /** 全部发送失败号码，包含未开通 WhatsApp。 */
    FAILED("failed", List.of(
            DataPackagePoolStatus.RETRYABLE_FAILED.code(),
            DataPackagePoolStatus.UNREGISTERED.code())),

    /** 已确认未开通 WhatsApp 的号码。 */
    FAIL_404("fail_404", List.of(DataPackagePoolStatus.UNREGISTERED.code()));

    private final String apiValue;
    private final List<Integer> poolStatusCodes;

    DataPackageUsageStatus(String apiValue, List<Integer> poolStatusCodes) {
        this.apiValue = apiValue;
        this.poolStatusCodes = List.copyOf(poolStatusCodes);
    }

    public String apiValue() {
        return apiValue;
    }

    public List<Integer> poolStatusCodes() {
        return poolStatusCodes;
    }

    /** 把查询字符串解析为稳定导出口径。 */
    public static DataPackageUsageStatus fromApi(String value) {
        String normalized = value == null ? ALL.apiValue : value.trim();
        for (DataPackageUsageStatus status : values()) {
            if (status.apiValue.equals(normalized)) {
                return status;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "号码使用状态不合法");
    }
}
