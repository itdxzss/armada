package com.armada.group.model.dto;

/**
 * 受控账号的本地群关系本次真正转为在群。
 *
 * @param accountId 受控账号 ID
 * @param groupJid WhatsApp 群 JID
 */
public record ControlledAccountGroupTransition(
        Long accountId,
        String groupJid) {
}
