package com.armada.account.model.dto;

import com.armada.account.model.enums.DeviceRegistrationFailureKind;

/** 当前订单原生结果；FAILED 附带固定类别、原因标识和有界详情，不接收凭据或验证码。 */
public record DeviceRegistrationResultDTO(String requestId, String phoneNumber, String outcome,
        DeviceRegistrationFailureKind failureKind, String failureCode, String failureDetail) {
    /** 避免完整号码被默认 record 字符串带入日志。 */
    @Override public String toString() { return "DeviceRegistrationResultDTO[redacted]"; }
}
