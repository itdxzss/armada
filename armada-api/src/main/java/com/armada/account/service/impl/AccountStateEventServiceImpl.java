package com.armada.account.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.account.state.AccountStateChangedSideEffect;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号协议事件落库服务实现。
 *
 * <p>实现现役协议层 {@code account.state_changed} 的最小状态收敛口径。
 * 本类不接 Kafka,只处理已经解析好的账号域事件,便于后续 listener 复用。</p>
 */
@Service
public class AccountStateEventServiceImpl implements AccountStateEventService {

    private static final Logger log = LoggerFactory.getLogger(AccountStateEventServiceImpl.class);

    /** account_state.state_source 列宽。 */
    private static final int STATE_SOURCE_MAX_LENGTH = 64;
    /** account_state.block_reason 列宽。 */
    private static final int BLOCK_REASON_MAX_LENGTH = 255;
    /** WhatsApp forbidden 断线码,按封禁处理。 */
    private static final int WA_CODE_FORBIDDEN = 403;
    /** WhatsApp connectionReplaced 断线码,按被抢登处理。 */
    private static final int WA_CODE_LOGIN_REPLACED = 440;
    /** 协议层在线状态。 */
    private static final String STATE_ONLINE = "ONLINE";
    /** 协议层正在验证登录态。 */
    private static final String STATE_VERIFYING = "VERIFYING";
    /** 协议层离线状态。 */
    private static final String STATE_OFFLINE = "OFFLINE";
    /** 协议层需重新认证状态。 */
    private static final String STATE_NEED_REAUTH = "NEED_REAUTH";
    /** 协议层确认当前连接被另一端登录替换。 */
    private static final String STATE_LOGIN_REPLACED = "LOGIN_REPLACED";
    /** 协议层已登出状态。 */
    private static final String STATE_LOGGED_OUT = "LOGGED_OUT";
    /** 协议层设备移除状态。 */
    private static final String STATE_DEVICE_REMOVED = "DEVICE_REMOVED";
    /** 协议层确认当前代理/IP 重连耗尽。 */
    private static final String STATE_PROXY_FAILED = "PROXY_FAILED";
    /** 协议层确认当前账号触发限流。 */
    private static final String STATE_RATE_LIMITED = "RATE_LIMITED";
    /** 批量离线命令来源,用于停止抢登循环。 */
    private static final String SOURCE_BATCH_OFFLINE_COMMAND = "batch_offline";
    /** 手动离线命令来源,用于停止抢登循环。 */
    private static final String SOURCE_MANUAL_OFFLINE_COMMAND = "manual_offline";
    /** 上游未给 semantic 时的默认来源。 */
    private static final String SOURCE_STATE_CHANGED = "STATE_CHANGED";
    /** 封禁状态来源。 */
    private static final String SOURCE_BANNED = "BANNED";
    /** 解绑状态来源。 */
    private static final String SOURCE_UNBOUND = "UNBOUND";
    /** 被抢登状态来源。 */
    private static final String SOURCE_LOGIN_REPLACED = "LOGIN_REPLACED";
    /** NEED_REAUTH + 403 收敛为封禁时写入的原因码。 */
    private static final String BAN_REASON_FORBIDDEN = "FORBIDDEN";

    private final AccountMapper accountMapper;
    private final AccountStateMapper stateMapper;
    private final IpProxyService ipProxyService;
    private final List<AccountStateChangedSideEffect> sideEffects;

    /**
     * 创建账号协议事件落库服务。
     *
     * @param accountMapper 账号主表 mapper
     * @param stateMapper    账号状态子表 mapper
     * @param ipProxyService IP 代理池服务
     * @param sideEffects    账号状态收敛后的业务结算扩展点
     */
    public AccountStateEventServiceImpl(AccountMapper accountMapper,
                                        AccountStateMapper stateMapper,
                                        IpProxyService ipProxyService,
                                        List<AccountStateChangedSideEffect> sideEffects) {
        this.accountMapper = accountMapper;
        this.stateMapper = stateMapper;
        this.ipProxyService = ipProxyService;
        this.sideEffects = List.copyOf(sideEffects);
    }

    /**
     * 应用协议层 {@code account.state_changed} 事件。
     *
     * <p>该方法在一个本地事务内完成状态收敛。找不到账号或事件时间早于当前状态时记录 warn 并跳过,
     * 避免历史脏事件阻塞 Kafka 分区消费,也避免延迟消息回滚较新的账号状态。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyStateChanged(AccountStateChangedEvent event) {
        validate(event);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            applyStateChangedInTenant(event);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 在事件租户上下文内收敛账号状态。
     *
     * <p>协议层事件可能乱序、延迟或带着旧的协议账号 ID 回来。这里先做账号定位和事件新鲜度校验,
     * 再按“会改变账号生命周期的状态优先,普通登录态兜底”的顺序落库。</p>
     */
    private void applyStateChangedInTenant(AccountStateChangedEvent event) {
        // 账号已删除或从未入库时只记录并跳过,避免 Kafka 消费被历史事件卡住。
        Account account = accountMapper.selectActiveById(event.accountId());
        if (account == null) {
            log.warn("协议账号状态事件跳过,账号不存在 tenantId={} accountId={} protocolAccountId={} "
                            + "from={} to={} semantic={} rawCode={}",
                    event.tenantId(), event.accountId(), event.protocolAccountId(),
                    event.from(), event.to(), event.semantic(), event.rawCode());
            return;
        }

        // 同一个业务账号重新绑定协议号后,旧协议 worker 仍可能回补状态;这里防止旧事件串改新账号状态。
        if (!event.protocolAccountId().equals(account.getProtocolAccountId())) {
            log.warn("协议账号状态事件跳过,账号映射不一致 tenantId={} accountId={} eventProtocolAccountId={} "
                            + "dbProtocolAccountId={} from={} to={}",
                    event.tenantId(), event.accountId(), event.protocolAccountId(),
                    account.getProtocolAccountId(), event.from(), event.to());
            return;
        }

        // occurredAt 是状态收敛的业务时间。协议未上报时退回本机时间,保证仍可更新 last_state_sync_time。
        long occurredAt = event.occurredAt() == null ? System.currentTimeMillis() : event.occurredAt();
        AccountState currentState = stateMapper.selectByAccountId(account.getId());

        // 延迟到达的旧离线/解绑事件不能覆盖更新的在线或抢登状态。
        if (isStaleEvent(currentState, occurredAt)) {
            log.warn("协议账号状态事件跳过,事件时间早于当前状态 accountId={} protocolAccountId={} from={} to={} "
                            + "eventOccurredAt={} currentLastStateSyncTime={}",
                    account.getId(), event.protocolAccountId(), event.from(), event.to(),
                    occurredAt, currentState.getLastStateSyncTime());
            return;
        }
        long updatedAt = System.currentTimeMillis();
        String stateSource = stateSource(event);

        // 生命周期状态会同时影响 account_state 和 login_state,例如被抢登、封禁、解绑、抢登中续上线。
        // 这类状态必须先处理,不能落入下面只更新登录态的兜底分支。
        if (applyLifecycleTransition(account, currentState, event, stateSource, occurredAt, updatedAt)) {
            releaseIpIfOffline(account, event, occurredAt);
            applySideEffects(account, event, occurredAt);
            log.info("协议账号状态事件已按生命周期收敛 accountId={} protocolAccountId={} from={} to={} "
                            + "semantic={} rawCode={} occurredAt={}",
                    account.getId(), event.protocolAccountId(), event.from(), event.to(),
                    event.semantic(), event.rawCode(), occurredAt);
            return;
        }

        // 剩余事件只表达在线/离线等登录态变化,不改变正常、被抢登、封禁、解绑等账号生命周期状态。
        AccountState row = updateRow(account.getId(), mapLoginState(event.to()), null,
                stateSource, null, occurredAt, updatedAt);
        int updated = stateMapper.updateLoginState(row);
        releaseIpIfOffline(account, event, occurredAt);
        applySideEffects(account, event, occurredAt);
        log.info("协议账号状态事件已更新登录态 accountId={} protocolAccountId={} from={} to={} loginState={} "
                        + "stateSource={} updated={} occurredAt={}",
                account.getId(), event.protocolAccountId(), event.from(), event.to(), row.getLoginState(),
                stateSource, updated, occurredAt);
    }

    private boolean applyLifecycleTransition(Account account,
                                             AccountState currentState,
                                             AccountStateChangedEvent event,
                                             String stateSource,
                                             long occurredAt,
                                             long updatedAt) {
        if (isLoginReplaced(event)) {
            if (isTakingOver(currentState)) {
                markTakingOverLogin(account, AccountLoginStateCode.OFFLINE, stateSource, occurredAt, updatedAt);
            } else {
                markLoginReplaced(account, occurredAt, updatedAt);
            }
            return true;
        }
        if (isTakingOver(currentState)) {
            if (STATE_ONLINE.equalsIgnoreCase(event.to())) {
                markTakingOverLogin(account, AccountLoginStateCode.ONLINE, stateSource, occurredAt, updatedAt);
                return true;
            }
            if (isUserOfflineStop(event)) {
                stateMapper.updateLoginAndAccountState(updateRow(account.getId(), AccountLoginStateCode.OFFLINE,
                        AccountStateCode.LOGIN_REPLACED, SOURCE_LOGIN_REPLACED, null, occurredAt, updatedAt));
                return true;
            }
            if (isTakeoverContinuableOffline(event)) {
                markTakingOverLogin(account, AccountLoginStateCode.OFFLINE, stateSource, occurredAt, updatedAt);
                return true;
            }
        }
        if (STATE_NEED_REAUTH.equalsIgnoreCase(event.to())) {
            if (isForbidden(event.rawCode())) {
                markBanned(account, occurredAt, updatedAt);
            } else {
                markUnbound(account, occurredAt, updatedAt);
            }
            return true;
        }
        if (STATE_LOGGED_OUT.equalsIgnoreCase(event.to()) || STATE_DEVICE_REMOVED.equalsIgnoreCase(event.to())) {
            markUnbound(account, occurredAt, updatedAt);
            return true;
        }
        if (STATE_ONLINE.equalsIgnoreCase(event.to())) {
            stateMapper.updateLoginState(updateRow(account.getId(), AccountLoginStateCode.ONLINE, null,
                    stateSource, null, occurredAt, updatedAt));
            stateMapper.markOnlineNormalState(updateRow(account.getId(), null, AccountStateCode.NORMAL,
                    stateSource, null, occurredAt, updatedAt));
            return true;
        }
        return false;
    }

    private static String stateSource(AccountStateChangedEvent event) {
        if (isLoginReplaced(event)) {
            return SOURCE_LOGIN_REPLACED;
        }
        return clamp(event.semantic() == null || event.semantic().isBlank()
                ? SOURCE_STATE_CHANGED
                : event.semantic(), STATE_SOURCE_MAX_LENGTH);
    }

    private void applySideEffects(Account account, AccountStateChangedEvent event, long occurredAt) {
        for (AccountStateChangedSideEffect sideEffect : sideEffects) {
            sideEffect.afterStateChanged(account, event, occurredAt);
        }
    }

    private static boolean isForbidden(Integer rawCode) {
        return rawCode != null && rawCode == WA_CODE_FORBIDDEN;
    }

    private static boolean isLoginReplaced(AccountStateChangedEvent event) {
        return STATE_LOGIN_REPLACED.equalsIgnoreCase(event.to())
                || STATE_LOGIN_REPLACED.equalsIgnoreCase(event.semantic())
                || (event.rawCode() != null && event.rawCode() == WA_CODE_LOGIN_REPLACED);
    }

    private static boolean isTakingOver(AccountState state) {
        return state != null
                && state.getAccountState() != null
                && state.getAccountState() == AccountStateCode.TAKING_OVER;
    }

    private static boolean isUserOfflineStop(AccountStateChangedEvent event) {
        return STATE_OFFLINE.equalsIgnoreCase(event.to())
                && (SOURCE_BATCH_OFFLINE_COMMAND.equalsIgnoreCase(event.source())
                || SOURCE_MANUAL_OFFLINE_COMMAND.equalsIgnoreCase(event.source()));
    }

    private static boolean isTakeoverContinuableOffline(AccountStateChangedEvent event) {
        return STATE_OFFLINE.equalsIgnoreCase(event.to())
                || STATE_RATE_LIMITED.equalsIgnoreCase(event.to())
                || STATE_RATE_LIMITED.equalsIgnoreCase(event.semantic())
                || isProxyFailedEvent(event);
    }

    private static boolean isStaleEvent(AccountState currentState, long occurredAt) {
        return currentState != null
                && currentState.getLastStateSyncTime() != null
                && occurredAt < currentState.getLastStateSyncTime();
    }

    private void markBanned(Account account, long occurredAt, long updatedAt) {
        AccountState row = updateRow(account.getId(), AccountLoginStateCode.OFFLINE, AccountStateCode.BANNED,
                SOURCE_BANNED, BAN_REASON_FORBIDDEN, occurredAt, updatedAt);
        stateMapper.updateLifecycleState(row);
        stateMapper.updateBlockReason(row);
    }

    private void markUnbound(Account account, long occurredAt, long updatedAt) {
        stateMapper.updateLifecycleState(updateRow(account.getId(), AccountLoginStateCode.OFFLINE,
                AccountStateCode.UNBOUND, SOURCE_UNBOUND, null, occurredAt, updatedAt));
    }

    private void markLoginReplaced(Account account, long occurredAt, long updatedAt) {
        stateMapper.updateLifecycleState(updateRow(account.getId(), AccountLoginStateCode.OFFLINE,
                AccountStateCode.LOGIN_REPLACED, SOURCE_LOGIN_REPLACED, null, occurredAt, updatedAt));
    }

    private void markTakingOverLogin(Account account,
                                     int loginState,
                                     String stateSource,
                                     long occurredAt,
                                     long updatedAt) {
        stateMapper.updateLoginAndAccountState(updateRow(account.getId(), loginState, AccountStateCode.TAKING_OVER,
                stateSource, null, occurredAt, updatedAt));
    }

    private void releaseIpIfOffline(Account account, AccountStateChangedEvent event, long occurredAt) {
        if (isProxyFailedEvent(event)) {
            ipProxyService.markBoundProxyUnavailableByAccount(account.getId(), occurredAt, STATE_PROXY_FAILED);
            return;
        }
        if (!shouldReleaseIp(event.to())) {
            return;
        }
        ipProxyService.releaseByAccount(account.getId());
    }

    private static boolean shouldReleaseIp(String state) {
        String normalized = state == null ? null : state.trim();
        return STATE_OFFLINE.equalsIgnoreCase(normalized)
                || STATE_LOGIN_REPLACED.equalsIgnoreCase(normalized)
                || STATE_NEED_REAUTH.equalsIgnoreCase(normalized)
                || STATE_RATE_LIMITED.equalsIgnoreCase(normalized)
                || STATE_LOGGED_OUT.equalsIgnoreCase(normalized)
                || STATE_DEVICE_REMOVED.equalsIgnoreCase(normalized);
    }

    private static boolean isProxyFailedEvent(AccountStateChangedEvent event) {
        return STATE_PROXY_FAILED.equalsIgnoreCase(event.to() == null ? null : event.to().trim())
                || STATE_PROXY_FAILED.equalsIgnoreCase(event.semantic() == null ? null : event.semantic().trim());
    }

    private static AccountState updateRow(Long accountId,
                                          Integer loginState,
                                          Integer accountState,
                                          String stateSource,
                                          String blockReason,
                                          long occurredAt,
                                          long updatedAt) {
        AccountState row = new AccountState();
        row.setAccountId(accountId);
        row.setLoginState(loginState);
        row.setAccountState(accountState);
        row.setStateSource(clamp(stateSource, STATE_SOURCE_MAX_LENGTH));
        row.setBlockReason(clamp(blockReason, BLOCK_REASON_MAX_LENGTH));
        row.setLastStateSyncTime(occurredAt);
        row.setUpdatedAt(updatedAt);
        return row;
    }

    private static Integer mapLoginState(String state) {
        String normalized = state == null ? null : state.trim();
        if (STATE_ONLINE.equalsIgnoreCase(normalized)) {
            return AccountLoginStateCode.ONLINE;
        }
        if (STATE_VERIFYING.equalsIgnoreCase(normalized)) {
            return AccountLoginStateCode.PENDING_ONLINE;
        }
        return AccountLoginStateCode.OFFLINE;
    }

    private static void validate(AccountStateChangedEvent event) {
        if (event == null || event.tenantId() == null || event.accountId() == null
                || event.protocolAccountId() == null || event.protocolAccountId().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议账号状态事件缺少账号定位字段");
        }
        if (event.to() == null || event.to().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议账号状态事件缺少目标状态");
        }
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
