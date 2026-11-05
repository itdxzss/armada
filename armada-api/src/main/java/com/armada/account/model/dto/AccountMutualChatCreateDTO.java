package com.armada.account.model.dto;

/** 分组互聊配置；文本与账号范围在创建时冻结。 */
public record AccountMutualChatCreateDTO(Long leftGroupId, Long rightGroupId, Integer intervalSeconds,
        String requestId, String previewToken, String leftText, String rightText) {}
