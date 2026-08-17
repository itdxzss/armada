package com.armada.group.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.AccountGroupMembershipReportService;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号当前群列表回报落库服务实现。
 *
 * <p>Kafka listener 线程没有 HTTP 租户上下文,本服务按事件中的 {@code tenantId}
 * 临时重建 {@link TenantContext}。baseline 和当前账号群关系只写新模型；
 * {@code group_link} 仅继续承载稳定数字句柄和历史/上控后兼容标记。</p>
 */
@Service
public class AccountGroupMembershipReportServiceImpl implements AccountGroupMembershipReportService {

    private static final Logger log = LoggerFactory.getLogger(AccountGroupMembershipReportServiceImpl.class);
    private static final int BASELINE_PENDING = AccountGroupBaselineStateCode.PENDING;

    private final AccountGroupCurrentSnapshotMapper currentSnapshotMapper;
    private final AccountGroupMembershipSnapshotService snapshotService;
    private final AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;
    private final MarketingNewGroupImmediateSendService immediateSendService;
    private final GroupClassificationService classificationService;

    /**
     * 创建账号当前群列表回报落库服务。
     *
     * @param currentSnapshotMapper 新模型账号上下文 mapper
     * @param snapshotService  账号可见群关系快照写入服务
     * @param currentSnapshotPersistence 新群模型账号快照持久化服务
     * @param immediateSendService 新群首次即时营销服务
     * @param classificationService 历史群与上控后群分类服务
     */
    public AccountGroupMembershipReportServiceImpl(AccountGroupCurrentSnapshotMapper currentSnapshotMapper,
                                                   AccountGroupMembershipSnapshotService snapshotService,
                                                   AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence,
                                                   MarketingNewGroupImmediateSendService immediateSendService,
                                                   GroupClassificationService classificationService) {
        this.currentSnapshotMapper = currentSnapshotMapper;
        this.snapshotService = snapshotService;
        this.currentSnapshotPersistence = currentSnapshotPersistence;
        this.immediateSendService = immediateSendService;
        this.classificationService = classificationService;
    }

    /**
     * 应用协议层 {@code account.groups_reported} 回报事件。
     *
     * <p>待拍账号先保存首次 baseline，随后按同一完整性判断写入旧表与新的群组当前事实表；
     * 只有确认完整的快照才会校准缺失关系；新增群资格由新模型当前事实决定。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyGroupsReported(AccountGroupsReportedEvent event) {
        validate(event);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            long now = System.currentTimeMillis();
            long syncAt = event.reportedAt() == null ? now : event.reportedAt();
            Context baselineRow = currentSnapshotMapper.selectContext(event.accountId());
            if (baselineRow == null) {
                log.warn("账号群列表事件找不到活跃账号 tenantId={} accountId={} protocolAccountId={} eventId={}",
                        event.tenantId(), event.accountId(), event.protocolAccountId(), event.eventId());
                return;
            }
            if (!currentProtocolBinding(baselineRow, event.protocolAccountId())) {
                log.warn("账号群列表事件协议句柄已过期 tenantId={} accountId={} eventId={} source={}",
                        event.tenantId(), event.accountId(), event.eventId(), event.source());
                return;
            }
            boolean pendingBaseline = baselineState(baselineRow) == BASELINE_PENDING;
            ProtocolBackend observedBackend = ProtocolBackend.fromProtocolId(baselineRow.protocolId());
            if (pendingBaseline) {
                capturePendingBaseline(event, observedBackend, syncAt, now);
            }
            boolean snapshotComplete = completeSnapshot(event, baselineRow);
            List<AccountGroupMembershipSnapshot> legacyGroups =
                    snapshotService.replaceVisibleGroups(
                    event.accountId(),
                    event.groups(),
                    snapshotComplete,
                    syncAt,
                    event.eventId(),
                    event.source(),
                    observedBackend);
            AccountGroupMembershipChangeSet changes = currentSnapshotPersistence.replaceVisibleGroups(
                    event.accountId(), event.groups(), snapshotComplete, syncAt, event.eventId(),
                    legacyGroups);
            if (!pendingBaseline && !changes.addedGroups().isEmpty()) {
                List<MarketingNewGroupDTO> addedGroups = changes.addedGroups().stream()
                        .map(group -> new MarketingNewGroupDTO(
                                group.groupLinkId(), group.groupJid(), group.groupName()))
                        .toList();
                immediateSendService.enqueueNewGroups(event.accountId(), addedGroups, now);
            }
            log.info("账号群列表事件已回写 eventId={} source={} reportedAt={} tenantId={} accountId={} "
                            + "protocolAccountId={} currentGroups={} addedGroups={} snapshotComplete={} "
                            + "skippedGroupCount={} addedGroupJidSample={} currentGroupJidSample={}",
                    event.eventId(), event.source(), event.reportedAt(), event.tenantId(), event.accountId(),
                    event.protocolAccountId(), changes.currentGroups().size(), changes.addedGroups().size(),
                    snapshotComplete, zero(event.skippedGroupCount()),
                    jidSample(changes.addedGroups().stream().map(group -> group.groupJid()).toList()),
                    jidSample(event.groups().stream().map(AccountGroupsReportedEvent.Group::groupJid).toList()));
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 兜底处理待拍账号的异步群回报。
     *
     * <p>定时同步已不再扫描待拍账号,但历史 outbox 或并发中的旧命令仍可能回报群列表。
     * 这里只允许账号仍处于待拍状态时捕获 baseline JID 与轻量载荷已有群名。当前群 membership
     * 由调用方继续按本次全量回报刷新。</p>
     *
     * @param event  协议层账号群列表回报事件
     * @param syncAt 协议查询时间(epoch 毫秒)
     * @param now    本次落库时间(epoch 毫秒)
     */
    private void capturePendingBaseline(
            AccountGroupsReportedEvent event,
            ProtocolBackend observedBackend,
            long syncAt,
            long now) {
        List<GroupClassificationCandidate> candidates = event.groups().stream()
                .filter(java.util.Objects::nonNull)
                .map(group -> new GroupClassificationCandidate(
                        null, normalizeJid(group.groupJid()), blankToNull(group.subject())))
                .toList();
        classificationService.captureHistoricalBaseline(candidates, observedBackend, now);
        log.info("待拍账号群基线已由异步回报捕获 eventId={} source={} reportedAt={} tenantId={} "
                        + "accountId={} protocolAccountId={} rawGroups={}",
                event.eventId(), event.source(), event.reportedAt(), event.tenantId(), event.accountId(),
                event.protocolAccountId(), event.groups().size());
    }

    /**
     * 读取账号 baseline 状态并处理历史空值。
     *
     * <p>历史数据若状态为空,按待拍处理,避免在没有明确 baseline 的情况下把旧群写入可见 membership。</p>
     *
     * @param baselineRow 账号 baseline 状态行
     * @return baseline 状态码
     */
    private static int baselineState(Context baselineRow) {
        Integer state = baselineRow.baselineState();
        return state == null ? BASELINE_PENDING : state;
    }

    private static boolean currentProtocolBinding(Context baselineRow,
                                                  String eventProtocolAccountId) {
        String current = blankToNull(baselineRow.protocolAccountId());
        String reported = blankToNull(eventProtocolAccountId);
        return current != null && current.equals(reported);
    }

    private static boolean completeSnapshot(AccountGroupsReportedEvent event,
                                            Context baselineRow) {
        boolean explicitlyComplete = Boolean.TRUE.equals(event.snapshotComplete())
                && zero(event.skippedGroupCount()) == 0;
        if (event.snapshotComplete() == null
                && zero(event.skippedGroupCount()) == 0
                && ProtocolBackend.fromProtocolId(baselineRow.protocolId()) == ProtocolBackend.WEB) {
            return true;
        }
        return explicitlyComplete;
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 返回最多 5 个非空群 JID,用于有界排障日志。
     */
    private static List<String> jidSample(Iterable<String> groupJids) {
        List<String> sample = new ArrayList<>(5);
        for (String groupJid : groupJids) {
            if (groupJid != null && !groupJid.isBlank()) {
                sample.add(groupJid);
                if (sample.size() == 5) {
                    break;
                }
            }
        }
        return sample;
    }

    /**
     * 校验异步群回报事件的最小必需字段。
     *
     * <p>事件来自 Kafka,缺少租户、账号或群列表时无法安全恢复租户上下文和写入账号维度事实,
     * 因此直接抛业务异常让调用链进入失败处理。</p>
     *
     * @param event 协议层账号群列表回报事件
     */
    private static void validate(AccountGroupsReportedEvent event) {
        if (event == null || event.tenantId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群列表事件缺少 tenantId");
        }
        if (event.accountId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群列表事件缺少 accountId");
        }
        if (event.groups() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群列表事件缺少 groups");
        }
    }

    /**
     * 归一化协议层返回的群 JID。
     *
     * @param value 原始群 JID
     * @return 去除首尾空白后的群 JID;空值返回 null
     */
    private static String normalizeJid(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized;
    }

    /**
     * 将空字符串统一收敛为 null。
     *
     * @param value 原始字符串
     * @return 非空白字符串或 null
     */
    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
