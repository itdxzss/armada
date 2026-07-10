package com.armada.account.model.dto;

import com.armada.account.model.command.AccountLifecycleCommandItem;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;

/**
 * 批量账号生命周期命令请求体。
 *
 * <p>新前端应传 {@code accounts} 以显式指定每个账号的协议后端。保留 {@code ids}
 * 是为了兼容已有调用方,这类旧请求仍按原服务方法处理。</p>
 */
public record AccountLifecycleBatchDTO(
        List<Long> ids,
        List<AccountLifecycleAccountDTO> accounts
) {

    /**
     * 是否使用带协议后端的新请求体。
     *
     * @return true 表示请求包含 accounts
     */
    public boolean hasAccounts() {
        return accounts != null && !accounts.isEmpty();
    }

    /**
     * 转换为账号生命周期命令项。
     *
     * @return 命令项列表
     */
    public List<AccountLifecycleCommandItem> commandItems() {
        if (!hasAccounts()) {
            return List.of();
        }
        return accounts.stream()
                .map(account -> new AccountLifecycleCommandItem(account.id(), account.protocolBackend()))
                .toList();
    }

    /**
     * 批量账号生命周期命令请求项。
     *
     * @param id              账号 ID
     * @param protocolBackend 本次命令的协议后端
     */
    public record AccountLifecycleAccountDTO(
            Long id,
            ProtocolBackend protocolBackend
    ) {
    }
}
