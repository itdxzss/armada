package com.armada.account.model.dto;
import java.util.List;
/** 创建/预览契约；accountIds 与 accountGroupIds 二选一，空集不表示全部。 */
public record AccountTierCheckRequestDTO(List<Long> accountGroupIds, List<Long> accountIds,
        boolean includePersonal, Integer mode, List<Long> probeAccountIds, String name,
        Long sourceTaskId, String requestId, String previewToken) {}
