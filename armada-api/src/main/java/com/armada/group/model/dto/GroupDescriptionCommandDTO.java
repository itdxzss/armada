package com.armada.group.model.dto;

/**
 * 修改 WhatsApp 真实群描述请求。
 *
 * @param accountId   操作账号 ID
 * @param description 新群描述;null 或空字符串表示清空
 */
public record GroupDescriptionCommandDTO(Long accountId, String description) {
}
