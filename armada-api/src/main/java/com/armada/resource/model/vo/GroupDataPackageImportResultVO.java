package com.armada.resource.model.vo;

/** 拉群数据包接口ImportResultVO。 */
public record GroupDataPackageImportResultVO(Long importId, String mode, Integer generation, int totalRows, int acceptedRows, int invalidRows, int duplicatedRows, int privacyFilteredRows, int phoneCountAfterImport) { }
