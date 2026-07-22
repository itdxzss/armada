package com.armada.group.model.vo;

/** 营销运行时批量查询账号群关系的复合键。 */
public record AccountGroupMembershipLookup(Long accountId, String groupJid) {
}
