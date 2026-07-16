package com.armada.account.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 从账号主表批量解析协议命令所需的最小账号引用。
 *
 * <p>查询仍由账号域执行，MyBatis 租户插件和软删除条件负责限定可见范围。返回顺序按调用方传入的
 * 账号顺序重建，避免数据库返回顺序改变任务派发与命令 ID 的对应关系。</p>
 */
@Service
public class AccountProtocolLookupServiceImpl implements AccountProtocolLookupService {

    /** 账号域持久化入口，用于批量读取当前租户可见的有效账号。 */
    private final AccountMapper accountMapper;

    /**
     * 创建账号协议身份查询服务。
     *
     * @param accountMapper 账号域 Mapper
     */
    public AccountProtocolLookupServiceImpl(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>协议字段不完整的账号会被过滤；协议后端由冻结的 {@code protocol_id} 解析，调用方无需再次
     * 猜测 Web 或 Android 路由。</p>
     */
    @Override
    public List<ProtocolAccountRef> findActiveProtocolRefs(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        List<Long> requestedIds = new LinkedHashSet<>(accountIds).stream()
                .filter(id -> id != null)
                .toList();
        if (requestedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Account> accountsById = new LinkedHashMap<>();
        for (Account account : accountMapper.selectActiveByIds(requestedIds)) {
            if (hasText(account.getProtocolId())
                    && hasText(account.getProtocolAccountId())
                    && hasText(account.getWsPhone())) {
                accountsById.putIfAbsent(account.getId(), account);
            }
        }
        return requestedIds.stream()
                .map(accountsById::get)
                .filter(account -> account != null)
                .map(account -> new ProtocolAccountRef(
                        account.getId(),
                        ProtocolBackend.fromProtocolId(account.getProtocolId()),
                        account.getProtocolAccountId(),
                        account.getWsPhone()))
                .toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
