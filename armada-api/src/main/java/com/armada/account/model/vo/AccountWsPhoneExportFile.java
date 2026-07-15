package com.armada.account.model.vo;

/** @param exportedCount TXT 中实际写入的唯一号码数量 */
public record AccountWsPhoneExportFile(String filename, byte[] bytes, int exportedCount) {
}
