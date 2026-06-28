package com.armada.account.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
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
    /** 协议层在线状态。 */
    private static final String STATE_ONLINE = "ONLINE";
    /** 协议层需重新认证状态。 */
    private static final String STATE_NEED_REAUTH = "NEED_REAUTH";
    /** 协议层已登出状态。 */
    private static final String STATE_LOGGED_OUT = "LOGGED_OUT";
    /** 协议层设备移除状态。 */
    private static final String STATE_DEVICE_REMOVED = "DEVICE_REMOVED";
    /** 上游未给 semantic 时的默认来源。 */
    private static final String SOURCE_STATE_CHANGED = "STATE_CHANGED";
    /** 封禁状态来源。 */
    private static final String SOURCE_BANNED = "BANNED";
    /** 解绑状态来源。 */
    private static final String SOURCE_UNBOUND = "UNBOUND";
    /** 解绑账号重新在线后的恢复来源。 */
    private static final String SOURCE_RECOVERED = "RECOVERED";
    /** NEED_REAUTH + 403 收敛为封禁时写入的原因码。 */
    private static final String BAN_REASON_FORBIDDEN = "FORBIDDEN";

    private final AccountMapper accountMapper;
    private final AccountStateMapper stateMapper;

    /**
     * 创建账号协议事件落库服务。
     *
     * @param accountMapper 账号主表 mapper
     * @param stateMapper   账号状态子表 mapper
     */
    public AccountStateEventServiceImpl(AccountMapper accountMapper, AccountStateMapper stateMapper) {
        this.accountMapper = accountMapper;
        this.stateMapper = stateMapper;
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
        Account account = accountMapper.selectActiveByProtocolAccountId(event.protocolAccountId());
        if (account == null) {
            log.warn("协议账号状态事件跳过,账号不存在 protocolAccountId={} from={} to={} semantic={} rawCode={}",
                    event.protocolAccountId(), event.from(), event.to(), event.semantic(), event.rawCode());
            return;
        }

        long occurredAt = event.occurredAt() == null ? System.currentTimeMillis() : event.occurredAt();
        AccountState currentState = stateMapper.selectByAccountId(account.getId());
        if (isStaleEvent(currentState, occurredAt)) {
            log.warn("协议账号状态事件跳过,事件时间早于当前状态 accountId={} protocolAccountId={} from={} to={} "
                            + "eventOccurredAt={} currentLastStateSyncTime={}",
                    account.getId(), event.protocolAccountId(), event.from(), event.to(),
                    occurredAt, currentState.getLastStateSyncTime());
            return;
        }
        long updatedAt = System.currentTimeMillis();
        String stateSource = clamp(event.semantic() == null || event.semantic().isBlank()
                ? SOURCE_STATE_CHANGED
                : event.semantic(), STATE_SOURCE_MAX_LENGTH);

        if (applyLifecycleTransition(account, event, stateSource, occurredAt, updatedAt)) {
            log.info("协议账号状态事件已按生命周期收敛 accountId={} protocolAccountId={} from={} to={} "
                            + "semantic={} rawCode={} occurredAt={}",
                    account.getId(), event.protocolAccountId(), event.from(), event.to(),
                    event.semantic(), event.rawCode(), occurredAt);
            return;
        }

        AccountState row = updateRow(account.getId(), mapLoginState(event.to()), null,
                stateSource, null, occurredAt, updatedAt);
        int updated = stateMapper.updateLoginState(row);
        log.info("协议账号状态事件已更新登录态 accountId={} protocolAccountId={} from={} to={} loginState={} "
                        + "stateSource={} updated={} occurredAt={}",
                account.getId(), event.protocolAccountId(), event.from(), event.to(), row.getLoginState(),
                stateSource, updated, occurredAt);
    }

    private boolean applyLifecycleTransition(Account account,
                                             AccountStateChangedEvent event,
                                             String stateSource,
                                             long occurredAt,
                                             long updatedAt) {
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
            stateMapper.recoverUnboundState(updateRow(account.getId(), null, AccountStateCode.NORMAL,
                    SOURCE_RECOVERED, null, occurredAt, updatedAt));
            return true;
        }
        return false;
    }

    private static boolean isForbidden(Integer rawCode) {
        return rawCode != null && rawCode == WA_CODE_FORBIDDEN;
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
        return STATE_ONLINE.equalsIgnoreCase(state == null ? null : state.trim())
                ? AccountLoginStateCode.ONLINE
                : AccountLoginStateCode.OFFLINE;
    }

    private static void validate(AccountStateChangedEvent event) {
        if (event == null || event.protocolAccountId() == null || event.protocolAccountId().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "协议账号状态事件缺少 protocolAccountId");
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
