package com.armada.account.model.dto;

/**
 * 在事务内读取并锁定的账号状态，不含凭据。
 *
 * <p>protocolAccountId、protocolId 用于导出完成时让协议删除该号的数据（WB 切片 52）。</p>
 */
public record AccountExportCandidate(Long id, Long groupId, Long dispatchedAt,
        Integer accountState, Integer loginState, Long marketingTaskId, Long ownerUserId,
        String protocolAccountId, String protocolId) { }
