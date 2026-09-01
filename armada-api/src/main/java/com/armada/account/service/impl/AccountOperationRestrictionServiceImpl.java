package com.armada.account.service.impl;

import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.enums.AccountOperationRestrictionStatus;
import com.armada.account.model.enums.AccountPullerRestrictionStatus;
import com.armada.account.model.vo.AccountOperationRestrictionClearVO;
import com.armada.account.model.vo.AccountPullerRestrictionSnapshot;
import com.armada.account.model.vo.AccountPullerRestrictionSummary;
import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 拉人受限事实写入统一账号操作限制状态的账号域实现。 */
@Service
public class AccountOperationRestrictionServiceImpl implements AccountOperationRestrictionService {

    private static final Logger log = LoggerFactory.getLogger(
            AccountOperationRestrictionServiceImpl.class);
    private static final long RESTRICTION_MILLIS = 86_400_000L;
    private static final int RECOVERY_BATCH_SIZE = 500;
    private static final int MAX_MANUAL_CLEAR_ACCOUNTS = 2_000;

    private final AccountStateMapper stateMapper;

    /** @param stateMapper 账号状态 Mapper */
    public AccountOperationRestrictionServiceImpl(AccountStateMapper stateMapper) {
        this.stateMapper = stateMapper;
    }

    /** {@inheritDoc} */
    @Override
    public boolean restrictPulling(Long accountId, String reasonCode, long occurredAt, long now) {
        long candidateUntil = fallbackUntil(occurredAt);
        if (!validRestriction(accountId, occurredAt, candidateUntil, now)) {
            return false;
        }
        return stateMapper.markPullingRestricted(
                accountId, normalizeReasonCode(
                        reasonCode, AccountOperationRestrictionStatus.PULLING_RESTRICTED),
                occurredAt, candidateUntil, now) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean restrictMessageSending(
            Long accountId, String reasonCode, long occurredAt, long now) {
        long candidateUntil = fallbackUntil(occurredAt);
        if (!validRestriction(accountId, occurredAt, candidateUntil, now)) {
            return false;
        }
        return stateMapper.markFallbackMessageRestricted(
                accountId, normalizeReasonCode(
                        reasonCode, AccountOperationRestrictionStatus.MESSAGE_SENDING_RESTRICTED),
                occurredAt, candidateUntil, now) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean restrictPlatformMessageSending(
            Long accountId, String reasonCode, long occurredAt, Long restrictedUntil, long now) {
        long candidateUntil = restrictedUntil == null
                ? fallbackUntil(occurredAt) : restrictedUntil;
        if (accountId == null || occurredAt <= 0 || candidateUntil <= occurredAt || now <= 0) {
            return false;
        }
        return stateMapper.markPlatformMessageRestricted(
                accountId, normalizeReasonCode(
                        reasonCode, AccountOperationRestrictionStatus.MESSAGE_SENDING_RESTRICTED),
                occurredAt, candidateUntil, now) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean clearPlatformMessageSending(Long accountId, long occurredAt, long now) {
        if (accountId == null || occurredAt <= 0 || now <= 0) {
            return false;
        }
        return stateMapper.clearPlatformMessageRestriction(accountId, occurredAt, now) == 1;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountOperationRestrictionClearVO clearOperationRestrictionsManually(
            List<Long> accountIds, long clearedAt) {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
        }
        if (clearedAt <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "人工解除时间无效");
        }
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        for (Long accountId : accountIds) {
            if (accountId == null || accountId <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 必须为正整数");
            }
            distinctIds.add(accountId);
        }
        if (distinctIds.size() > MAX_MANUAL_CLEAR_ACCOUNTS) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "单次最多手动解除 2000 个账号的业务风控");
        }
        List<Long> normalizedIds = List.copyOf(distinctIds);
        int cleared = stateMapper.clearOperationRestrictionsManually(
                normalizedIds, clearedAt);
        log.info("账号业务风控手动解除 requested={} cleared={}",
                normalizedIds.size(), cleared);
        return new AccountOperationRestrictionClearVO(normalizedIds.size(), cleared);
    }

    private static long fallbackUntil(long occurredAt) {
        return occurredAt > Long.MAX_VALUE - RESTRICTION_MILLIS
                ? Long.MAX_VALUE : occurredAt + RESTRICTION_MILLIS;
    }

    private static boolean validRestriction(
            Long accountId, long occurredAt, long candidateUntil, long now) {
        return accountId != null && occurredAt > 0 && candidateUntil > occurredAt
                && now > 0 && candidateUntil > now;
    }

    /** {@inheritDoc} */
    @Override
    public int restoreExpired(long now) {
        if (now <= 0) {
            return 0;
        }
        int total = 0;
        int restored;
        do {
            restored = stateMapper.restoreExpiredAccountOperationRestrictions(
                    now, RECOVERY_BATCH_SIZE);
            total += restored;
        } while (restored == RECOVERY_BATCH_SIZE);
        return total;
    }

    /** {@inheritDoc} */
    @Override
    public Map<Long, AccountPullerRestrictionSnapshot> findPullerRestrictionsByAccountIds(
            List<Long> accountIds) {
        List<Long> normalizedIds = normalizeIds(accountIds);
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AccountPullerRestrictionSnapshot> result = new LinkedHashMap<>();
        for (AccountState row : stateMapper.selectPullerRestrictionsByAccountIds(normalizedIds)) {
            result.put(row.getAccountId(), snapshot(row));
        }
        return Map.copyOf(result);
    }

    /** {@inheritDoc} */
    @Override
    public AccountPullerRestrictionSummary summarizePullersByGroupId(
            Long accountGroupId, long serverNow) {
        if (accountGroupId == null) {
            return new AccountPullerRestrictionSummary(serverNow, 0, null);
        }
        List<AccountState> rows = stateMapper.selectPullerRestrictionsByGroupId(accountGroupId);
        Long nextUntil = rows.stream()
                .map(AccountState::getCooldownUntil)
                .filter(Objects::nonNull)
                .min(Long::compareTo)
                .orElse(null);
        return new AccountPullerRestrictionSummary(serverNow, rows.size(), nextUntil);
    }

    private static AccountPullerRestrictionSnapshot snapshot(AccountState row) {
        boolean pullingRestricted = AccountOperationRestrictionStatus.restrictsPulling(
                row.getMuteStatus());
        int status = pullingRestricted
                ? AccountPullerRestrictionStatus.RESTRICTED.code()
                : AccountPullerRestrictionStatus.ALLOWED.code();
        return new AccountPullerRestrictionSnapshot(
                row.getAccountId(), status,
                status == AccountPullerRestrictionStatus.RESTRICTED.code()
                        ? row.getCooldownUntil() : null,
                status == AccountPullerRestrictionStatus.RESTRICTED.code()
                        ? row.getRestrictionReasonCode() : null);
    }

    private static String normalizeReasonCode(
            String reasonCode, AccountOperationRestrictionStatus incomingStatus) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return incomingStatus == AccountOperationRestrictionStatus.MESSAGE_SENDING_RESTRICTED
                    ? "MESSAGE_SENDING_RESTRICTED" : "PULLING_RESTRICTED";
        }
        String normalized = reasonCode.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static List<Long> normalizeIds(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        return new LinkedHashSet<>(accountIds).stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
