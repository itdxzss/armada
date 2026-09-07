package com.armada.account.model.vo;

/**
 * 手机导入/退出确认结果，不表示协议已上线。
 * @param batchId 已提交导入批次 ID
 * @param onlinePhase 上传后 WAITING_LOGOUT；退出确认已放行时 QUEUED（可幂等重试）
 */
public record DeviceImportVO(Long batchId, String onlinePhase) {
}
