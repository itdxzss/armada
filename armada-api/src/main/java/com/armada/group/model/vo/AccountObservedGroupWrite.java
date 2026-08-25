package com.armada.group.model.vo;

/**
 * 账号群快照批量登记兼容句柄的单行写入。
 *
 * <p>{@code insertGroupName} 在全新插入时保证列表名称非空；
 * {@code observedGroupName} 只在唯一键冲突时决定是否覆盖已有真实名称，二者不能混用。</p>
 */
public record AccountObservedGroupWrite(
        Long groupLinkId,
        String linkUrl,
        String insertGroupName,
        String observedGroupName,
        Integer origin,
        Integer membershipState,
        Integer syncProtocolMask,
        long createdAt,
        long updatedAt) {
}
