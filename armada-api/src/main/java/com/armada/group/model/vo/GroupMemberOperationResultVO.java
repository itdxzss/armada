package com.armada.group.model.vo;

/**
 * 单个群成员操作结果。
 *
 * @param jid    成员 JID
 * @param status 稳定结果码
 * @param reason 面向运营的结果说明
 */
public record GroupMemberOperationResultVO(
        String jid,
        String status,
        String reason) {
}
