package com.armada.account.state;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.task.service.PullTaskPullerAccountStateService;
import com.armada.task.service.PullTaskPullerAccountStateService.Unavailability;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 把账号离线、封禁和解绑事实转交普通拉群任务域处理。 */
@Component
public class PullTaskPullerAccountStateChangedSideEffect
        implements AccountStateChangedSideEffect {

    private static final int WA_CODE_FORBIDDEN = 403;
    private static final int WA_CODE_LOGIN_REPLACED = 440;
    private static final String STATE_OFFLINE = "OFFLINE";
    private static final String STATE_PROXY_FAILED = "PROXY_FAILED";
    private static final String STATE_LOGIN_REPLACED = "LOGIN_REPLACED";
    private static final String STATE_NEED_REAUTH = "NEED_REAUTH";
    private static final String STATE_LOGGED_OUT = "LOGGED_OUT";
    private static final String STATE_DEVICE_REMOVED = "DEVICE_REMOVED";

    private final PullTaskPullerAccountStateService pullTasks;

    /** @param pullTasks 普通拉群拉手账号状态服务 */
    public PullTaskPullerAccountStateChangedSideEffect(
            PullTaskPullerAccountStateService pullTasks) {
        this.pullTasks = pullTasks;
    }

    /**
     * 正式账号状态事务内同步任务拉手事实；短暂验证和重连状态不触发切换。
     *
     * @param account 已完成状态收敛的账号
     * @param event 协议账号状态事件
     * @param occurredAt 状态发生时间(epoch 毫秒)
     */
    @Override
    public void afterStateChanged(
            Account account,
            AccountStateChangedEvent event,
            long occurredAt) {
        Unavailability unavailability = classify(event);
        if (unavailability == null) {
            return;
        }
        pullTasks.markUnavailable(
                event.tenantId(), account.getId(), unavailability, occurredAt);
    }

    private static Unavailability classify(AccountStateChangedEvent event) {
        String state = normalized(event.to());
        String semantic = normalized(event.semantic());
        if (STATE_LOGIN_REPLACED.equals(state)
                || STATE_LOGIN_REPLACED.equals(semantic)
                || (event.rawCode() != null
                        && event.rawCode() == WA_CODE_LOGIN_REPLACED)) {
            return Unavailability.OFFLINE;
        }
        if (STATE_NEED_REAUTH.equals(state)) {
            return event.rawCode() != null && event.rawCode() == WA_CODE_FORBIDDEN
                    ? Unavailability.BANNED : Unavailability.UNBOUND;
        }
        if (STATE_LOGGED_OUT.equals(state) || STATE_DEVICE_REMOVED.equals(state)) {
            return Unavailability.UNBOUND;
        }
        if (STATE_OFFLINE.equals(state)
                || STATE_PROXY_FAILED.equals(state)
                || STATE_PROXY_FAILED.equals(semantic)) {
            return Unavailability.OFFLINE;
        }
        return null;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
