package com.armada.account.model.dto;

import java.util.List;

/** requestId 为客户端生成的 UUID；ids 是本次明确勾选的账号快照。 */
public record AccountExportCreateDTO(String requestId, List<Long> ids) { }
