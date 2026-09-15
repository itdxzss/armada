package com.armada.resource.model.vo;

/** 拉群数据包接口ImportVO。 */
public record GroupDataPackageImportVO(Long id, String mode, String fileName, Integer generation, Integer status, Integer totalRows, Integer acceptedRows, Integer invalidRows, Integer duplicatedRows, Integer privacyFilteredRows, String failureReason, Long createdBy, Long createdAt, Long finishedAt) { }
