package com.armada.account.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.vo.AccountProbeVO;
import com.armada.account.model.vo.AccountStatusVO;
import com.armada.account.service.AccountLifecycleCommandService;
import com.armada.platform.protocol.model.result.ProtocolAccountStatus;
import com.armada.platform.protocol.model.result.ProtocolProbeResult;
import com.armada.platform.protocol.port.AccountLifecyclePort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号生命周期诊断应用服务实现。
 */
@Service
public class AccountLifecycleCommandServiceImpl implements AccountLifecycleCommandService {

    private static final Logger log = LoggerFactory.getLogger(AccountLifecycleCommandServiceImpl.class);

    private final AccountMapper accountMapper;
    private final AccountLifecyclePort accountLifecyclePort;

    public AccountLifecycleCommandServiceImpl(AccountMapper accountMapper,
                                              AccountLifecyclePort accountLifecyclePort) {
        this.accountMapper = accountMapper;
        this.accountLifecyclePort = accountLifecyclePort;
    }

    @Override
    public AccountStatusVO refreshStatus(Long accountId) {
        Account account = loadAccount(accountId);
        String protocolAccountId = requireProtocolAccountId(account);
        ProtocolAccountStatus status = accountLifecyclePort.status(protocolAccountId);
        log.info("账号协议状态主动刷新 accountId={} protocolAccountId={} state={} stateSource={}",
                account.getId(), protocolAccountId, status == null ? null : status.state(),
                status == null ? null : status.stateSource());
        return toStatusVO(account.getId(), protocolAccountId, status);
    }

    @Override
    public AccountProbeVO probe(Long accountId) {
        Account account = loadAccount(accountId);
        String protocolAccountId = requireProtocolAccountId(account);
        ProtocolProbeResult probe = accountLifecyclePort.probe(protocolAccountId);
        log.info("账号协议主动探活 accountId={} protocolAccountId={} ok={} latencyMs={} reasonCode={}",
                account.getId(), protocolAccountId, probe != null && probe.ok(),
                probe == null ? null : probe.latencyMs(), probe == null ? null : probe.reasonCode());
        return toProbeVO(account.getId(), protocolAccountId, probe);
    }

    private Account loadAccount(Long accountId) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
        }
        Account account = accountMapper.selectActiveById(accountId);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账号不存在或已删除: " + accountId);
        }
        return account;
    }

    private static String requireProtocolAccountId(Account account) {
        String protocolAccountId = account.getProtocolAccountId();
        if (protocolAccountId == null || protocolAccountId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议账号 ID 为空: " + account.getId());
        }
        return protocolAccountId;
    }

    private static AccountStatusVO toStatusVO(Long accountId,
                                              String fallbackProtocolAccountId,
                                              ProtocolAccountStatus status) {
        if (status == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议层账号状态响应为空");
        }
        String protocolAccountId = status.protocolAccountId() == null || status.protocolAccountId().isBlank()
                ? fallbackProtocolAccountId
                : status.protocolAccountId();
        return new AccountStatusVO(
                accountId,
                protocolAccountId,
                status.state(),
                status.stateSource(),
                status.accountType(),
                toEpochMilli(status.lastStateSyncTime()),
                toEpochMilli(status.cooldownUntil()),
                toEpochMilli(status.reportedAt()),
                status.needReauth(),
                status.reauthReason(),
                status.workerId());
    }

    private static AccountProbeVO toProbeVO(Long accountId,
                                            String protocolAccountId,
                                            ProtocolProbeResult probe) {
        if (probe == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议层账号探活响应为空");
        }
        return new AccountProbeVO(
                accountId,
                protocolAccountId,
                probe.ok(),
                toEpochMilli(probe.probedAt()),
                probe.latencyMs(),
                probe.reasonCode());
    }

    private static Long toEpochMilli(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
