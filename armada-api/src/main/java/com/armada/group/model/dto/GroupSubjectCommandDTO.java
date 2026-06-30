package com.armada.group.model.dto;

/**
 * 修改 WhatsApp 真实群名称请求。
 *
 * @param accountId 操作账号 ID
 * @param subject   新群名称
 */
public record GroupSubjectCommandDTO(Long accountId, String subject) {
}
