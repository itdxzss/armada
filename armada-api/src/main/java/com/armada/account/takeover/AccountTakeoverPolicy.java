package com.armada.account.takeover;

import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.enums.AccountCreatorDeletionLifecycle;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 被挤状态收敛及自动续上线共用准入，关闭开关时保留原有账号行为。 */
@Service
public class AccountTakeoverPolicy {

    /** 被挤事件需要写入的生命周期结果。 */
    public enum ReplacedDecision {
        /** 保留已有封禁、解绑等终态，仅收敛真实登录态。 */
        KEEP_LIFECYCLE,
        /** 停止全局抢登并保持被抢登状态。 */
        LOGIN_REPLACED,
        /** 保持抢登中并允许后续提交后恢复。 */
        TAKING_OVER
    }

    private static final Logger log = LoggerFactory.getLogger(AccountTakeoverPolicy.class);
    private static final Set<Integer> TERMINAL_STATES = Set.of(AccountStateCode.BANNED, AccountStateCode.EXPORTED,
            AccountStateCode.UNBOUND, AccountStateCode.RESTRICTED, AccountStateCode.DEREGISTERED);
    private static final Set<Integer> TAKEOVER_STATES = Set.of(AccountStateCode.NORMAL,
            AccountStateCode.LOGIN_REPLACED, AccountStateCode.TAKING_OVER);

    private final AccountAutoTakeoverProperties properties;
    private final AccountTakeoverBreaker breaker;
    private final AccountCreatorReservationLookup reservations;

    /**
     * @param properties 账号侧开关
     * @param breaker 持久熔断
     * @param reservations 建群人归属查询
     */
    public AccountTakeoverPolicy(AccountAutoTakeoverProperties properties, AccountTakeoverBreaker breaker,
                                 AccountCreatorReservationLookup reservations) {
        this.properties = properties;
        this.breaker = breaker;
        this.reservations = reservations;
    }

    /** @return 是否开启账号侧自动抢登策略 */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 按终态、注销归属、用户意图和熔断优先级决定被挤后生命周期。
     * @param account 当前租户账号
     * @param state 被挤事件前的账号状态
     * @param occurredAt 被挤事实时间，毫秒
     * @return 应保留或写入的生命周期决定
     */
    public ReplacedDecision onLoginReplaced(Account account, AccountState state, long occurredAt) {
        Integer lifecycle = state == null ? null : state.getAccountState();
        if (!isEnabled()) {
            return Integer.valueOf(AccountStateCode.TAKING_OVER).equals(lifecycle)
                    ? ReplacedDecision.TAKING_OVER : ReplacedDecision.LOGIN_REPLACED;
        }
        if (lifecycle != null && TERMINAL_STATES.contains(lifecycle)) {
            return ReplacedDecision.KEEP_LIFECYCLE;
        }
        var reservation = reservations.find(account.getId());
        if (reservation.isPresent()
                && !AccountCreatorDeletionLifecycle.RESERVED.name().equals(reservation.get().lifecycle())) {
            return ReplacedDecision.LOGIN_REPLACED;
        }
        if (!eligibleLifecycleAndIntent(state)) {
            return ReplacedDecision.LOGIN_REPLACED;
        }
        if (breaker.recordKick(account, occurredAt) == AccountTakeoverBreaker.KickResult.TRIPPED) {
            log.warn("自动抢登熔断 accountId={} occurredAt={}", account.getId(), occurredAt);
            return ReplacedDecision.LOGIN_REPLACED;
        }
        return reservation.isPresent() ? ReplacedDecision.LOGIN_REPLACED : ReplacedDecision.TAKING_OVER;
    }

    /**
     * @param state 账号状态
     * @return 用户是否明确期望账号保持离线
     */
    public boolean desiredOffline(AccountState state) {
        return state != null && Integer.valueOf(AccountLoginStateCode.OFFLINE).equals(state.getDesiredLoginState());
    }

    /**
     * 为现有“7 且离线且未禁言”检查补充用户意图、熔断和注销归属防线。
     * @param accountId 当前租户账号
     * @param state 调用方刚读取的账号状态
     * @return 是否通过新增防线；开关关闭时恒为 true
     */
    public boolean canReonline(Long accountId, AccountState state) {
        if (!isEnabled()) {
            return true;
        }
        if (accountId == null || desiredOffline(state) || breaker.isTripped(accountId)) {
            return false;
        }
        if (reservations.find(accountId).isPresent()) {
            log.info("自动抢登跳过预留或注销账号,由所属执行行恢复 accountId={}", accountId);
            return false;
        }
        return true;
    }

    /**
     * 补偿扫描在发命令前复核全局被抢登账号的恢复条件。
     * @param accountId 当前租户账号
     * @param state 当前账号状态
     * @return 开关开启且处于未受限、期望在线、未熔断、未预留的被抢登离线状态
     */
    public boolean canAutoTakeover(Long accountId, AccountState state) {
        return isEnabled() && state != null
                && Integer.valueOf(AccountStateCode.LOGIN_REPLACED).equals(state.getAccountState())
                && Integer.valueOf(AccountLoginStateCode.OFFLINE).equals(state.getLoginState())
                && state.getMuteStatus() == null && canReonline(accountId, state);
    }

    /**
     * 预留账号仅允许原租户、原任务和执行行恢复；不进入全局抢登态。
     * @param accountId 当前租户账号
     * @param taskId 预留任务
     * @param executionId 预留执行行
     * @param state 当前锁定的账号状态
     * @return 是否允许原子抢占待上线
     */
    public boolean canReonlineReservedCreator(long accountId, long taskId, long executionId, AccountState state) {
        if (!isEnabled() || state == null || state.getMuteStatus() != null || desiredOffline(state)
                || !Integer.valueOf(AccountLoginStateCode.OFFLINE).equals(state.getLoginState())
                || !(Integer.valueOf(AccountStateCode.NORMAL).equals(state.getAccountState())
                    || Integer.valueOf(AccountStateCode.LOGIN_REPLACED).equals(state.getAccountState()))
                || breaker.isTripped(accountId)) {
            return false;
        }
        return reservations.find(accountId).filter(reservation ->
                AccountCreatorDeletionLifecycle.RESERVED.name().equals(reservation.lifecycle())
                        && Objects.equals(reservation.tenantId(), TenantContext.get())
                        && Objects.equals(reservation.taskId(), taskId)
                        && Objects.equals(reservation.groupExecutionId(), executionId)).isPresent();
    }

    /**
     * @param accountIds 人工抢登选择的账号
     * @param now 清零时间，毫秒
     */
    public void reset(List<Long> accountIds, long now) {
        if (isEnabled()) {
            breaker.reset(accountIds, now);
        }
    }

    private boolean eligibleLifecycleAndIntent(AccountState state) {
        return state != null && state.getAccountState() != null
                && TAKEOVER_STATES.contains(state.getAccountState())
                && state.getMuteStatus() == null && !desiredOffline(state);
    }
}
