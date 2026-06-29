package com.armada.group.model.dto;

import java.util.List;

/**
 * 批量实时预览群链接请求。
 *
 * @param accountId Armada 本地账号 ID,用于解析协议账号句柄
 * @param ids       group_link.id 列表
 */
public record GroupLinkPreviewDTO(Long accountId, List<Long> ids) {
}
