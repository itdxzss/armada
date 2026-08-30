package com.armada.hyperlink.task.service;

import com.armada.account.model.entity.Account;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.state.AccountStateChangedSideEffect;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAccountUsageStatus;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 将账号域已验真的终态失效事件冻结到进行中超链任务 usage。 */
@Component
public class HyperlinkAccountStateChangedSideEffect implements AccountStateChangedSideEffect {
    private static final int WA_FORBIDDEN = 403;
    private static final int WA_LOGIN_REPLACED = 440;
    private final HyperlinkTaskAccountUsageMapper mapper;

    public HyperlinkAccountStateChangedSideEffect(HyperlinkTaskAccountUsageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void afterStateChanged(Account account, AccountStateChangedEvent event,
            long occurredAt) {
        InvalidFact fact = classify(event);
        if (fact == null) return;
        mapper.markActiveByAccountInvalid(event.tenantId(), account.getId(),
                fact.usageStatus(),
                fact.code(), fact.reason(), occurredAt);
    }

    private static InvalidFact classify(AccountStateChangedEvent event) {
        String state = normalized(event.to());
        String semantic = normalized(event.semantic());
        if ("LOGIN_REPLACED".equals(state) || "LOGIN_REPLACED".equals(semantic)
                || event.rawCode() != null && event.rawCode() == WA_LOGIN_REPLACED) {
            return new InvalidFact(HyperlinkTaskAccountUsageStatus.INVALID.code(),
                    "LOGIN_REPLACED", "账号登录被替换");
        }
        if ("NEED_REAUTH".equals(state)) {
            if (event.rawCode() != null && event.rawCode() == WA_FORBIDDEN) {
                return new InvalidFact(HyperlinkTaskAccountUsageStatus.BANNED.code(),
                        "WA_403", "账号被平台禁用");
            }
            return new InvalidFact(HyperlinkTaskAccountUsageStatus.INVALID.code(),
                    "NEED_REAUTH", "账号需要重新认证");
        }
        if ("LOGGED_OUT".equals(state)) {
            return new InvalidFact(HyperlinkTaskAccountUsageStatus.INVALID.code(),
                    "LOGGED_OUT", "账号已登出");
        }
        if ("DEVICE_REMOVED".equals(state)) {
            return new InvalidFact(HyperlinkTaskAccountUsageStatus.INVALID.code(),
                    "DEVICE_REMOVED", "主设备已移除关联");
        }
        // OFFLINE/PROXY_FAILED/RECONNECTING 是可恢复波动，不计入市场封号率。
        return null;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record InvalidFact(int usageStatus, String code, String reason) { }
}
