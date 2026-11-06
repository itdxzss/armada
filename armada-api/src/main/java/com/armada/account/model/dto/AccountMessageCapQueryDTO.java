package com.armada.account.model.dto;

import java.util.List;

/**
 * 批量查询新聊天额度的请求体。
 *
 * @param ids 当前租户的账号 ID，去重后最多 100 个
 * @param debug 为 true 时返回每个账号的原始响应与完整解码内容，仅供排查和实验
 */
public record AccountMessageCapQueryDTO(List<Long> ids, boolean debug) {
}
