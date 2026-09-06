package com.armada.account.model.vo;

/**
 * 手机导入受理结果；只表示事务已提交并入队，不表示协议已上线。
 * @param batchId 已提交导入批次 ID
 * @param onlinePhase 本次受理阶段 QUEUED
 */
public record DeviceImportVO(Long batchId, String onlinePhase) {
}
