package com.armada.account.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 从账号主表批量解析协议命令所需的最小账号引用。
 *
 * <p>查询仍由账号域执行，MyBatis 租户插件和软删除条件负责限定可见范围。返回顺序按调用方传入的
 * 账号顺序重建，避免数据库返回顺序改变任务派发与命令 ID 的对应关系。</p>
 */
@Service
public class AccountProtocolLookupServiceImpl implements AccountProtocolLookupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountProtocolLookupServiceImpl.class);

    /** 风险允许：account_state.risk_status 未风控。NULL 同样由 SQL 视为允许。 */
    private static final int RISK_ALLOWED = 1;

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

    /** {@inheritDoc} */
    @Override
    public Optional<ProtocolAccountRef> findActiveProtocolRef(Long accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return toProtocolRef(accountMapper.selectActiveById(accountId));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ProtocolAccountRef> findRandomOnlineNormalByGroupId(Long groupId) {
        if (groupId == null) {
            LOGGER.info("账号协议随机选号无候选: groupId为空");
            return Optional.empty();
        }
        Optional<ProtocolAccountRef> selected = toProtocolRef(accountMapper.selectRandomOnlineNormalByGroupId(
                groupId,
                AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE,
                RISK_ALLOWED));
        if (selected.isEmpty()) {
            LOGGER.info("账号协议随机选号无候选: groupId={}", groupId);
        }
        return selected;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ProtocolAccountRef> findRandomOnlineNormalWebByGroupId(Long groupId) {
        if (groupId == null) {
            LOGGER.info("账号协议 Web 随机选号无候选: groupId为空");
            return Optional.empty();
        }
        Optional<ProtocolAccountRef> selected = toProtocolRef(
                accountMapper.selectRandomOnlineNormalWebByGroupId(
                        groupId,
                        AccountStateCode.NORMAL,
                        AccountLoginStateCode.ONLINE,
                        RISK_ALLOWED,
                        ProtocolBackend.WEB.name()));
        if (selected.isEmpty()) {
            LOGGER.info("账号协议 Web 随机选号无候选: groupId={}", groupId);
        }
        return selected;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, ProtocolAccountRef> findActiveProtocolRefsByPhones(List<String> phones) {
        List<String> requestedPhones = normalizePhones(phones);
        if (requestedPhones.isEmpty()) {
            return Map.of();
        }
        Map<String, ProtocolAccountRef> refsByPhone = new LinkedHashMap<>();
        for (Account account : accountMapper.selectActiveByWsPhones(requestedPhones)) {
            toProtocolRef(account).ifPresent(ref -> refsByPhone.putIfAbsent(ref.wsPhone(), ref));
        }
        Map<String, ProtocolAccountRef> refs = new LinkedHashMap<>();
        for (String phone : requestedPhones) {
            ProtocolAccountRef ref = refsByPhone.get(phone);
            if (ref != null) {
                refs.put(phone, ref);
            }
        }
        LOGGER.info("账号协议号码批量查询完成: requestedCount={}, matchedCount={}",
                requestedPhones.size(), refs.size());
        return Collections.unmodifiableMap(refs);
    }

    /**
     * {@inheritDoc}
     *
     * <p>协议字段不完整的账号会被过滤；协议后端由 {@code protocol_id} 解析。存量 Web 账号的
     * {@code protocol_id} 允许为空，由 {@link ProtocolBackend#fromProtocolId(String)} 统一回退为 Web，
     * 调用方无需重复猜测协议路由。</p>
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
        Map<Long, ProtocolAccountRef> refsById = new LinkedHashMap<>();
        for (Account account : accountMapper.selectActiveByIds(requestedIds)) {
            toProtocolRef(account).ifPresent(ref -> refsById.putIfAbsent(ref.armadaAccountId(), ref));
        }
        return requestedIds.stream()
                .map(refsById::get)
                .filter(ref -> ref != null)
                .toList();
    }

    private static List<String> normalizePhones(List<String> phones) {
        if (phones == null || phones.isEmpty()) {
            return List.of();
        }
        return phones.stream()
                .filter(phone -> phone != null)
                .map(String::trim)
                .filter(phone -> !phone.isEmpty())
                .distinct()
                .toList();
    }

    private static Optional<ProtocolAccountRef> toProtocolRef(Account account) {
        if (account == null
                || !hasText(account.getProtocolAccountId())
                || !hasText(account.getWsPhone())) {
            return Optional.empty();
        }
        return Optional.of(new ProtocolAccountRef(
                account.getId(),
                ProtocolBackend.fromProtocolId(account.getProtocolId()),
                account.getProtocolAccountId(),
                account.getWsPhone()));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
