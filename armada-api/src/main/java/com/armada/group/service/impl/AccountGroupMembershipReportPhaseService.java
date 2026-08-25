package com.armada.group.service.impl;

import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.model.vo.AccountGroupCompatibilitySnapshot;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.model.vo.GroupClassificationPlan;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 账号群回报的两个可恢复数据库阶段。 */
@Service
public class AccountGroupMembershipReportPhaseService {

    private static final Logger log = LoggerFactory.getLogger(
            AccountGroupMembershipReportPhaseService.class);

    private final AccountGroupCurrentSnapshotMapper currentSnapshotMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final AccountGroupMembershipSnapshotService snapshotService;
    private final GroupClassificationService classificationService;
    private final AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;
    private final MarketingNewGroupImmediateSendService immediateSendService;
    private final GroupMetadataSyncTaskService metadataSyncTaskService;

    /**
     * 创建账号群回报阶段服务。
     *
     * @param currentSnapshotMapper 账号绑定与完整快照水位数据访问
     * @param snapshotService 旧兼容句柄与创建者快照服务
     * @param classificationService 历史群和上控后群分类服务
     * @param currentSnapshotPersistence 当前群事实持久化服务
     * @param immediateSendService 新群即时营销服务
     * @param metadataSyncTaskService 群详情耐久同步任务服务
     */
    public AccountGroupMembershipReportPhaseService(
            AccountGroupCurrentSnapshotMapper currentSnapshotMapper,
            GroupLinkMapper groupLinkMapper,
            AccountGroupMembershipSnapshotService snapshotService,
            GroupClassificationService classificationService,
            AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence,
            MarketingNewGroupImmediateSendService immediateSendService,
            GroupMetadataSyncTaskService metadataSyncTaskService) {
        this.currentSnapshotMapper = currentSnapshotMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.snapshotService = snapshotService;
        this.classificationService = classificationService;
        this.currentSnapshotPersistence = currentSnapshotPersistence;
        this.immediateSendService = immediateSendService;
        this.metadataSyncTaskService = metadataSyncTaskService;
    }

    /**
     * 第一阶段登记兼容句柄、分类事实与创建者兼容字段。
     *
     * <p>该阶段先独立提交，使第二阶段锁等待失败时 Kafka 重放只需幂等刷新，不会继续持有
     * {@code group_link} 锁。待拍 baseline 在句柄解析后按稳定 ID 标为历史群，避免重复登记。</p>
     *
     * @param event 账号群回报事件
     * @param observedBackend 本次观察协议后端
     * @param pendingBaseline 本次是否仍在捕获首次 baseline
     * @param snapshotComplete 本次快照是否完整
     * @param syncAt 协议群快照时间(epoch 毫秒)
     * @return 是否接受本事件及已解析的稳定兼容群句柄
     */
    @Transactional(
            rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRES_NEW)
    public CompatibilityPhaseResult prepareCompatibility(
            AccountGroupsReportedEvent event,
            ProtocolBackend observedBackend,
            boolean pendingBaseline,
            boolean snapshotComplete,
            long syncAt) {
        Context lockedContext = currentSnapshotMapper.selectContextForUpdate(event.accountId());
        if (!accepts(lockedContext, event.protocolAccountId(), syncAt)) {
            log.warn("账号群列表兼容阶段跳过过期事件 accountId={} protocolAccountId={} "
                            + "eventId={} syncAt={} lastCompleteAt={}",
                    event.accountId(), event.protocolAccountId(), event.eventId(), syncAt,
                    lockedContext == null ? null : lockedContext.lastCompleteAt());
            return CompatibilityPhaseResult.stale();
        }
        AccountGroupCompatibilitySnapshot prepared = snapshotService.prepareVisibleGroups(
                event.accountId(), event.groups(), snapshotComplete, syncAt,
                event.eventId(), event.source(), observedBackend);
        List<AccountGroupMembershipSnapshot> groups = prepared.groups();
        GroupClassificationPlan classificationPlan = prepared.classificationPlan();
        if (pendingBaseline && !groups.isEmpty()) {
            classificationPlan = classificationPlan.merge(
                    classificationService.stageHistoricalBaseline(
                    groups.stream()
                            .map(group -> new GroupClassificationCandidate(
                                    group.groupLinkId(), group.groupJid(), group.groupName()))
                            .toList(),
                    observedBackend,
                    syncAt));
        }
        return CompatibilityPhaseResult.accepted(groups, classificationPlan);
    }

    /**
     * 第二阶段原子提交当前群事实与新群营销副作用。
     *
     * @param event 账号群回报事件
     * @param snapshotComplete 本次快照是否完整
     * @param syncAt 协议群快照时间(epoch 毫秒)
     * @param legacyGroups 第一阶段解析的稳定兼容群句柄
     * @param pendingBaseline 本次是否仍在捕获首次 baseline
     * @return 当前群与新增群归约结果
     */
    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW)
    public AccountGroupMembershipChangeSet applyCurrentSnapshot(
            AccountGroupsReportedEvent event,
            boolean snapshotComplete,
            long syncAt,
            List<AccountGroupMembershipSnapshot> legacyGroups,
            GroupClassificationPlan classificationPlan,
            boolean pendingBaseline) {
        Context lockedContext = currentSnapshotMapper.selectContextForUpdate(event.accountId());
        if (!accepts(lockedContext, event.protocolAccountId(), syncAt)) {
            log.warn("账号群列表当前事实阶段跳过过期事件 accountId={} protocolAccountId={} "
                            + "eventId={} syncAt={} lastCompleteAt={}",
                    event.accountId(), event.protocolAccountId(), event.eventId(), syncAt,
                    lockedContext == null ? null : lockedContext.lastCompleteAt());
            return new AccountGroupMembershipChangeSet(List.of(), List.of());
        }
        lockLegacyGroups(legacyGroups);
        AccountGroupMembershipChangeSet changes = currentSnapshotPersistence.replaceVisibleGroups(
                event.accountId(), event.groups(), snapshotComplete, syncAt,
                event.eventId(), legacyGroups);
        long now = System.currentTimeMillis();
        enqueueClassificationTasks(classificationPlan, syncAt);
        if (!pendingBaseline && !changes.addedGroups().isEmpty()) {
            List<MarketingNewGroupDTO> addedGroups = changes.addedGroups().stream()
                    .map(group -> new MarketingNewGroupDTO(
                            group.groupLinkId(), group.groupJid(), group.groupName()))
                    .toList();
            immediateSendService.enqueueNewGroups(
                    event.accountId(), addedGroups, syncAt > 0L ? syncAt : now);
        }
        return changes;
    }

    /**
     * 手工历史群刷新在独立事务内按账号、旧句柄、当前事实、任务的统一顺序落库。
     *
     * @param accountId 账号 ID
     * @param groups 协议本次返回的完整群集合
     * @param syncAt 协议查询时间(epoch 毫秒)
     * @param eventId 手工刷新事件 ID
     * @param prepared 第一阶段已提交的兼容句柄与分类计划
     * @return 当前群归约结果
     */
    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW)
    public AccountGroupMembershipChangeSet applyManualCurrentSnapshot(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            long syncAt,
            String eventId,
            AccountGroupCompatibilitySnapshot prepared) {
        if (currentSnapshotMapper.selectContextForUpdate(accountId) == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模型快照找不到活跃账号");
        }
        List<AccountGroupMembershipSnapshot> legacyGroups = prepared.groups();
        lockLegacyGroups(legacyGroups);
        AccountGroupMembershipChangeSet changes = currentSnapshotPersistence.replaceVisibleGroups(
                accountId, groups, true, syncAt, eventId, legacyGroups);
        enqueueClassificationTasks(prepared.classificationPlan(), syncAt);
        return changes;
    }

    private void lockLegacyGroups(List<AccountGroupMembershipSnapshot> legacyGroups) {
        List<Long> legacyGroupLinkIds = legacyGroups == null ? List.of() : legacyGroups.stream()
                .map(AccountGroupMembershipSnapshot::groupLinkId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (!legacyGroupLinkIds.isEmpty()) {
            groupLinkMapper.selectActiveByIdsForUpdate(legacyGroupLinkIds);
        }
    }

    private void enqueueClassificationTasks(GroupClassificationPlan classificationPlan, long syncAt) {
        GroupClassificationPlan safePlan = classificationPlan == null
                ? GroupClassificationPlan.empty() : classificationPlan;
        if (!safePlan.newlyPersisted().isEmpty()) {
            metadataSyncTaskService.enqueueClassifications(
                    safePlan.newlyPersisted(), syncAt);
        }
        if (!safePlan.recoveryOnly().isEmpty()) {
            metadataSyncTaskService.reconcileClassifications(
                    safePlan.recoveryOnly(), syncAt);
        }
    }

    private static boolean accepts(
            Context context,
            String expectedProtocolAccountId,
            long syncAt) {
        if (context == null
                || expectedProtocolAccountId == null
                || !expectedProtocolAccountId.equals(context.protocolAccountId())) {
            return false;
        }
        return context.lastCompleteAt() == null
                || syncAt > context.lastCompleteAt();
    }

    /** 第一阶段接纳结果；空快照与过期事件必须显式区分。 */
    public record CompatibilityPhaseResult(
            boolean accepted,
            List<AccountGroupMembershipSnapshot> groups,
            GroupClassificationPlan classificationPlan) {

        public CompatibilityPhaseResult {
            groups = groups == null ? List.of() : List.copyOf(groups);
            classificationPlan = classificationPlan == null
                    ? GroupClassificationPlan.empty() : classificationPlan;
        }

        /** 接受本事件。 */
        public static CompatibilityPhaseResult accepted(
                List<AccountGroupMembershipSnapshot> groups,
                GroupClassificationPlan classificationPlan) {
            return new CompatibilityPhaseResult(true, groups, classificationPlan);
        }

        /** 本事件已被账号绑定或完整快照水位淘汰。 */
        public static CompatibilityPhaseResult stale() {
            return new CompatibilityPhaseResult(
                    false, List.of(), GroupClassificationPlan.empty());
        }
    }
}
