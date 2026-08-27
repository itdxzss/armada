package com.armada.group.service.impl;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMessageSendPermissionSnapshot;
import com.armada.group.model.vo.AccountGroupMembershipStatusSnapshot;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号群关系当前状态服务实现。
 *
 * <p>读取路径批量返回当前租户内已存在的关系状态；写入路径应用协议层账号自身的精确关系事实。
 * 写入前会切换到事件租户并核对账号当前协议句柄，防止迟到事件写入已经重新绑定的账号；事实时间与
 * 来源优先级的乱序保护由关系 upsert SQL 统一执行。</p>
 */
@Service
public class AccountGroupMembershipStatusServiceImpl implements AccountGroupMembershipStatusService {

    /** 群快照命令明确确认当前执行账号已不在群时使用的精确关系来源。 */
    public static final String GROUP_SNAPSHOT_NOT_JOINED_SOURCE = "GROUP_SNAPSHOT_NOT_JOINED";

    /** 关系事件应用结果的安全业务日志。 */
    private static final Logger log = LoggerFactory.getLogger(AccountGroupMembershipStatusServiceImpl.class);

    /** 账号群关系数据访问入口。 */
    private final AccountGroupMembershipMapper membershipMapper;

    /** 新模型账号和 baseline 上下文。 */
    private final AccountGroupCurrentSnapshotMapper currentSnapshotMapper;

    /** 协议事件观察到群时使用的统一群组池登记入口。 */
    private final GroupLinkRegistryService groupLinkRegistryService;

    /** 历史群与上控后群固化分类服务。 */
    private final GroupClassificationService classificationService;

    /** V120 新表精确关系事实写入入口。 */
    private final AccountGroupCurrentSnapshotPersistenceImpl currentPersistence;

    /**
     * 创建账号群关系状态服务。
     *
     * @param membershipMapper 账号群关系 Mapper
     * @param groupLinkRegistryService 群组池登记服务
     * @param classificationService 历史群与上控后群分类服务
     * @param currentPersistence V120 新表精确关系事实写入入口
     */
    public AccountGroupMembershipStatusServiceImpl(AccountGroupMembershipMapper membershipMapper,
                                                   AccountGroupCurrentSnapshotMapper currentSnapshotMapper,
                                                   GroupLinkRegistryService groupLinkRegistryService,
                                                   GroupClassificationService classificationService,
                                                   AccountGroupCurrentSnapshotPersistenceImpl currentPersistence) {
        this.membershipMapper = membershipMapper;
        this.currentSnapshotMapper = currentSnapshotMapper;
        this.groupLinkRegistryService = groupLinkRegistryService;
        this.classificationService = classificationService;
        this.currentPersistence = currentPersistence;
    }

    /**
     * 批量查询当前租户内指定账号与群的当前关系状态。
     *
     * <p>输入会去除空项、空 JID 和重复复合键；数据库中不存在当前关系的键不会返回快照，调用方可按
     * {@code UNCONFIRMED} 兼容策略决定是否发送。</p>
     *
     * @param lookups 账号 ID 与群 JID 复合键，可空
     * @return 已存在关系的不可变状态快照列表；没有有效输入或没有匹配关系时返回空列表
     */
    @Override
    public List<AccountGroupMembershipStatusSnapshot> findCurrentStatuses(
            List<AccountGroupMembershipLookup> lookups) {
        List<AccountGroupMembershipLookup> normalized = normalizeLookups(lookups);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return membershipMapper.selectCurrentStatuses(normalized).stream()
                .map(row -> new AccountGroupMembershipStatusSnapshot(
                        row.accountId(),
                        row.groupJid(),
                        AccountGroupMembershipStatus.fromCode(row.membershipStatus()),
                        row.statusUpdatedAt()))
                .toList();
    }

    /**
     * 批量查询当前租户内指定账号与群的当前发言权限。
     *
     * <p>只有群明确为管理员发言且账号明确为普通成员时返回 {@code false}；权限或角色事实不足时
     * 保留空值，由发送方按不误拦截策略处理。</p>
     *
     * @param lookups 账号 ID 与群 JID 复合键，可空
     * @return 已存在关系的发言权限快照；没有有效输入时返回空列表
     */
    @Override
    public List<AccountGroupMessageSendPermissionSnapshot> findCurrentMessageSendPermissions(
            List<AccountGroupMembershipLookup> lookups) {
        List<AccountGroupMembershipLookup> normalized = normalizeLookups(lookups);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return membershipMapper.selectCurrentMessageSendPermissions(normalized).stream()
                .map(row -> new AccountGroupMessageSendPermissionSnapshot(
                        row.accountId(), row.groupJid(), row.messageSendAllowed()))
                .toList();
    }

    /**
     * 应用协议层账号自身的精确群关系变化。
     *
     * <p>{@code add/remove/leave} 分别转换为 {@code IN_GROUP/NOT_IN_GROUP/LEFT}。WGP2 的 remove
     * 只能证明账号已不在群，不能可靠证明是被管理员踢出。账号不存在或事件协议句柄
     * 已过期时只记录安全日志并忽略；有效事件会在同一事务中登记群组池入口并更新账号群关系，最后恢复
     * 调用线程原有租户上下文。</p>
     *
     * @param event 已通过 platform 层结构校验的精确关系事实
     * @throws BusinessException 当租户、账号、协议句柄、群 JID、事实时间或动作非法时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyMembershipChanged(AccountGroupMembershipChangedEvent event) {
        validateEvent(event);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            Context account = currentSnapshotMapper.selectContext(event.accountId());
            if (account == null) {
                log.warn("账号群关系事件找不到活跃账号 eventId={} accountId={} action={} source={}",
                        event.eventId(), event.accountId(), event.action(), event.source());
                return;
            }
            if (!Objects.equals(normalizeJid(account.protocolAccountId()),
                    normalizeJid(event.protocolAccountId()))) {
                log.warn("账号群关系事件协议句柄已过期 eventId={} accountId={} action={} source={}",
                        event.eventId(), event.accountId(), event.action(), event.source());
                return;
            }
            Transition transition = transition(event.action());
            long now = System.currentTimeMillis();
            Long groupLinkId = groupLinkRegistryService.registerAccountObservedGroup(
                    event.groupJid().trim(),
                    null,
                    ProtocolBackend.fromProtocolId(account.protocolId()),
                    now);
            String presenceSource = GROUP_SNAPSHOT_NOT_JOINED_SOURCE.equals(event.source())
                    && transition.status() == AccountGroupMembershipStatus.NOT_IN_GROUP
                    ? GROUP_SNAPSHOT_NOT_JOINED_SOURCE : transition.source();
            currentPersistence.applySelfMembershipChanged(
                    event.accountId(),
                    event.groupJid().trim(),
                    transition.status(),
                    event.occurredAt(),
                    event.eventId(),
                    presenceSource);
            if (transition.status() == AccountGroupMembershipStatus.IN_GROUP) {
                classificationService.classifyMembershipAdded(
                        event.accountId(),
                        new GroupClassificationCandidate(
                                groupLinkId, event.groupJid().trim(), null),
                        event.occurredAt(),
                        now);
            }
            log.info("账号群关系事件已应用 eventId={} accountId={} action={} status={} source={}",
                    event.eventId(), event.accountId(), event.action(), transition.status().apiValue(),
                    transition.source());
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private static Transition transition(String action) {
        return switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "add" -> new Transition(AccountGroupMembershipStatus.IN_GROUP, "WGP2_ADD");
            case "remove" -> new Transition(AccountGroupMembershipStatus.NOT_IN_GROUP, "WGP2_REMOVE");
            case "leave" -> new Transition(AccountGroupMembershipStatus.LEFT, "WGP2_LEAVE");
            default -> throw new BusinessException(ErrorCode.VALIDATION, "账号群关系事件 action 非法");
        };
    }

    private static void validateEvent(AccountGroupMembershipChangedEvent event) {
        if (event == null || event.tenantId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群关系事件缺少 tenantId");
        }
        if (event.accountId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群关系事件缺少 accountId");
        }
        if (normalizeJid(event.protocolAccountId()) == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群关系事件缺少 protocolAccountId");
        }
        if (normalizeJid(event.groupJid()) == null || !event.groupJid().trim().endsWith("@g.us")) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群关系事件 groupJid 非法");
        }
        if (event.occurredAt() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群关系事件缺少 occurredAt");
        }
        transition(event.action() == null ? "" : event.action());
    }

    /**
     * 精确事件动作对应的目标关系状态和持久化来源。
     *
     * @param status 要写入的当前关系状态
     * @param source 用于同事实时间来源优先级判断的稳定来源码
     */
    private record Transition(AccountGroupMembershipStatus status, String source) {
    }

    private static List<AccountGroupMembershipLookup> normalizeLookups(
            List<AccountGroupMembershipLookup> lookups) {
        if (lookups == null || lookups.isEmpty()) {
            return List.of();
        }
        Set<AccountGroupMembershipLookup> normalized = new LinkedHashSet<>();
        for (AccountGroupMembershipLookup lookup : lookups) {
            if (lookup == null || lookup.accountId() == null) {
                continue;
            }
            String groupJid = normalizeJid(lookup.groupJid());
            if (groupJid != null) {
                normalized.add(new AccountGroupMembershipLookup(lookup.accountId(), groupJid));
            }
        }
        return normalized.stream().filter(Objects::nonNull).toList();
    }

    private static String normalizeJid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
