package com.armada.resource.model.vo;

/** 拉群数据包接口ExportVO。 */
public record GroupDataPackageExportVO(String filename, String contentType, byte[] bytes, long exportedCount) { }
