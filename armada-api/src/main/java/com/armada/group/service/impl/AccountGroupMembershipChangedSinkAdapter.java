package com.armada.group.service.impl;

import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountGroupMembershipChangedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolAccountGroupMembershipChangedSink;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 将 platform 层解析的账号群关系事件安全转换为 group 域事实。
 *
 * <p>适配器只接受当前账号自身的 {@code add/remove/leave} 事件，并在进入 group 域前归一化动作和群 JID。
 * participant 列表、操作者身份等协议细节不会进入 group 域，避免扩大敏感数据边界。</p>
 */
@Service
public class AccountGroupMembershipChangedSinkAdapter
        implements ProtocolAccountGroupMembershipChangedSink {

    /** Android 精确关系事件允许进入 group 域的动作集合。 */
    private static final Set<String> ACTIONS = Set.of("add", "remove", "leave");

    /** 账号群关系状态写入服务。 */
    private final AccountGroupMembershipStatusService statusService;

    /**
     * 创建精确关系事件适配器。
     *
     * @param statusService 负责校验账号绑定并应用当前关系状态的 group 域服务
     */
    public AccountGroupMembershipChangedSinkAdapter(AccountGroupMembershipStatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * 校验并应用协议层账号自身群关系变化。
     *
     * <p>方法要求 {@code selfParticipation=SELF}，并把动作统一为小写、群 JID 去除首尾空白后再交给
     * 状态服务。非本人事件不会降级为快照事件处理，而是作为非法输入拒绝。</p>
     *
     * @param event platform 层已解析的精确关系事件
     * @throws BusinessException 当事件为空、不是本人变化、动作不受支持或必要标识缺失时抛出
     */
    @Override
    public void handleMembershipChanged(ProtocolAccountGroupMembershipChangedEvent event) {
        if (event == null) {
            throw validation("账号群关系事件为空");
        }
        if (!"SELF".equals(event.selfParticipation())) {
            throw validation("账号群关系事件不是账号自身变化");
        }
        String action = normalizeAction(event.action());
        String groupJid = normalizeGroupJid(event.groupJid());
        statusService.applyMembershipChanged(new AccountGroupMembershipChangedEvent(
                event.tenantId(),
                event.accountId(),
                normalizeRequired(event.protocolAccountId(), "账号群关系事件缺少 protocolAccountId"),
                groupJid,
                action,
                event.occurredAt(),
                normalizeRequired(event.eventId(), "账号群关系事件缺少 eventId"),
                event.source()));
    }

    private static String normalizeAction(String value) {
        String normalized = normalizeRequired(value, "账号群关系事件缺少 action")
                .toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(normalized)) {
            throw validation("账号群关系事件 action 非法");
        }
        return normalized;
    }

    private static String normalizeGroupJid(String value) {
        String normalized = normalizeRequired(value, "账号群关系事件缺少 groupJid");
        if (!normalized.endsWith("@g.us")) {
            throw validation("账号群关系事件 groupJid 非法");
        }
        return normalized;
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw validation(message);
        }
        return value.trim();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
