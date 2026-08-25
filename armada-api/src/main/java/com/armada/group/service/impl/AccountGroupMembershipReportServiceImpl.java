package com.armada.group.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.service.AccountGroupMembershipReportService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final AccountGroupMembershipReportPhaseService phaseService;

    /**
     * 创建账号当前群列表回报落库服务。
     *
     * @param currentSnapshotMapper 新模型账号上下文 mapper
     * @param phaseService 可恢复的兼容写与当前事实事务阶段
     */
    public AccountGroupMembershipReportServiceImpl(AccountGroupCurrentSnapshotMapper currentSnapshotMapper,
                                                   AccountGroupMembershipReportPhaseService phaseService) {
        this.currentSnapshotMapper = currentSnapshotMapper;
        this.phaseService = phaseService;
    }

    /**
     * 应用协议层 {@code account.groups_reported} 回报事件。
     *
     * <p>兼容句柄与分类先在独立事务提交，随后当前群事实与新增群营销副作用在第二个事务原子提交。
     * 第二阶段锁等待失败时异常继续抛给 Kafka 重试，已提交的第一阶段可安全幂等重放；只有确认完整
     * 的快照才会校准缺失关系。</p>
     */
    @Override
    public void applyGroupsReported(AccountGroupsReportedEvent event) {
        validate(event);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            long syncAt = event.reportedAt();
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
            boolean snapshotComplete = completeSnapshot(event, baselineRow);
            if (staleSnapshot(syncAt, baselineRow.lastCompleteAt())) {
                log.info("账号群列表事件已被较新完整水位淘汰 eventId={} accountId={} syncAt={} "
                                + "lastCompleteAt={}",
                        event.eventId(), event.accountId(), syncAt, baselineRow.lastCompleteAt());
                return;
            }
            AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult preparation =
                    phaseService.prepareCompatibility(
                            event, observedBackend, pendingBaseline, snapshotComplete, syncAt);
            if (!preparation.accepted()) {
                return;
            }
            List<AccountGroupMembershipSnapshot> legacyGroups = preparation.groups();
            AccountGroupMembershipChangeSet changes = phaseService.applyCurrentSnapshot(
                    event, snapshotComplete, syncAt, legacyGroups,
                    preparation.classificationPlan(), pendingBaseline);
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

    private static boolean staleSnapshot(long syncAt, Long lastCompleteAt) {
        return lastCompleteAt != null && syncAt <= lastCompleteAt;
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
        if (event.reportedAt() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群列表事件缺少 reportedAt");
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
