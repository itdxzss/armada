package com.armada.group.model.dto;

/**
 * 修改 WhatsApp 群公告文本请求。
 *
 * @param accountId 操作账号 ID
 * @param text      公告文本
 */
public record GroupAnnouncementTextCommandDTO(Long accountId, String text) {
}
