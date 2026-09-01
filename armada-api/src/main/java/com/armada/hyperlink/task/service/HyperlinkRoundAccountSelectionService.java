package com.armada.hyperlink.task.service;

import com.armada.account.model.vo.AccountHyperlinkCandidateVO;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundAccountMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRound;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRoundAccount;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAccountUsageStatus;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 为既有 round 补齐稳定账号集合，并复用任务级 usage。 */
@Service
public class HyperlinkRoundAccountSelectionService {
    /** 冻结 defaultSubTaskNum，只控制扫描切片，不改变业务选号上限。 */
    private static final int DEFAULT_SUB_TASK_NUM = 50;

    private final HyperlinkTaskAccountUsageMapper usageMapper;
    private final HyperlinkTaskRoundAccountMapper roundAccountMapper;
    private final HyperlinkAccountCandidateSelector candidateSelector;

    public HyperlinkRoundAccountSelectionService(HyperlinkTaskAccountUsageMapper usageMapper,
            HyperlinkTaskRoundAccountMapper roundAccountMapper,
            HyperlinkAccountCandidateSelector candidateSelector) {
        this.usageMapper = usageMapper;
        this.roundAccountMapper = roundAccountMapper;
        this.candidateSelector = candidateSelector;
    }

    /**
     * 给一个处于 SELECTING 的 round 补足实际并发账号，返回可用账号数。
     */
    public int select(HyperlinkTask task, HyperlinkTaskRound round, long now) {
        roundAccountMapper.syncUnavailableFromUsage(round.getId(), now);
        List<HyperlinkTaskRoundAccount> selected = roundAccountMapper.selectByRoundId(round.getId());
        int selectedTotal = selected.size();
        int available = roundAccountMapper.countAvailableByRoundId(round.getId());
        int operationRestricted = roundAccountMapper.countOperationRestrictedByRoundId(
                round.getId());
        int selectedAgainstCap = Math.max(0, selectedTotal - operationRestricted);
        int selectionCap = selectionCap(task);
        int canAdd = Math.max(0, Math.min(selectionCap - available,
                task.getMaxUseAccount() == null || task.getMaxUseAccount() == 0
                        ? selectionCap : task.getMaxUseAccount() - selectedAgainstCap));
        if (canAdd > 0) {
            Set<Long> selectedAccountIds = new HashSet<>();
            selected.forEach(value -> selectedAccountIds.add(value.getAccountId()));
            int selectionNo = selectedTotal;
            Integer afterPriority = null;
            Long afterAccountId = null;
            while (canAdd > 0) {
                List<AccountHyperlinkCandidateVO> page = candidateSelector.select(
                        task, afterPriority, afterAccountId, DEFAULT_SUB_TASK_NUM, now);
                if (page.isEmpty()) { break; }
                AccountHyperlinkCandidateVO last = page.get(page.size() - 1);
                afterPriority = last.priority();
                afterAccountId = last.accountId();
                for (AccountHyperlinkCandidateVO account : page) {
                    if (canAdd == 0) { break; }
                    if (selectedAccountIds.contains(account.accountId())) { continue; }
                    HyperlinkTaskAccountUsage persisted = persistedUsage(
                            task, account, round.getRoundNo(), now);
                    if (persisted.getUsageStatus()
                            != HyperlinkTaskAccountUsageStatus.AVAILABLE.code()
                            || usageMapper.markSelectedRound(
                                    persisted.getId(), round.getRoundNo(), now) != 1) {
                        continue;
                    }
                    HyperlinkTaskRoundAccount row = roundAccount(
                            task, round, persisted, account, ++selectionNo, now);
                    if (roundAccountMapper.insertIgnore(row) == 1) {
                        selectedAccountIds.add(account.accountId());
                        canAdd--;
                    }
                }
                if (page.size() < DEFAULT_SUB_TASK_NUM) { break; }
            }
        }
        roundAccountMapper.syncUnavailableFromUsage(round.getId(), now);
        return roundAccountMapper.countAvailableByRoundId(round.getId());
    }

    /** 实际派发账号数受 concurrentNum 和当前模式账号上限共同约束。 */
    public int selectionCap(HyperlinkTask task) {
        int concurrent = task.getConcurrentNum() == 0
                ? Math.min(HyperlinkTaskConfigurationFactory.MAX_EXECUTING_ACCOUNTS,
                        candidateSelector.protocolCount()
                                * HyperlinkProtocolCapacityService.ACCOUNTS_PER_PROTOCOL)
                : task.getConcurrentNum();
        if (task.getMaxUseAccount() == null || task.getMaxUseAccount() == 0) {
            return concurrent;
        }
        return Math.min(task.getMaxUseAccount(), concurrent);
    }

    private HyperlinkTaskAccountUsage persistedUsage(HyperlinkTask task,
            AccountHyperlinkCandidateVO account, long roundNo, long now) {
        HyperlinkTaskAccountUsage proposed = usage(task, account, roundNo, now);
        usageMapper.insertIgnore(proposed);
        HyperlinkTaskAccountUsage persisted = usageMapper.selectByTaskAndAccount(
                task.getId(), account.accountId());
        if (persisted == null || persisted.getId() == null) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "任务账号使用事实未收敛");
        }
        return persisted;
    }

    private HyperlinkTaskAccountUsage usage(HyperlinkTask task,
            AccountHyperlinkCandidateVO account, long roundNo, long now) {
        HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
        usage.setHyperlinkTaskId(task.getId());
        usage.setAccountId(account.accountId());
        usage.setAccountPhoneSnapshot(account.wsPhone());
        usage.setSenderCountryIso2Snapshot(account.countryIso2());
        usage.setAccountTypeSnapshot(account.accountType());
        usage.setSenderDeviceOsSnapshot(account.deviceOs());
        usage.setAccountCreatedAtSnapshot(account.createdAt());
        ProtocolBackend backend = ProtocolBackend.fromExplicitProtocolId(account.protocolBackend());
        usage.setProtocolIdSnapshot(account.protocolId());
        usage.setProtocolAccountIdSnapshot(account.protocolAccountId());
        usage.setProtocolBackend(backend == ProtocolBackend.WEB ? 1 : 2);
        usage.setSuccessLimit(task.getAccountMaxSendNum());
        usage.setUsageStatus(HyperlinkTaskAccountUsageStatus.AVAILABLE.code());
        usage.setLastSelectedRoundNo(roundNo);
        usage.setNextSendAt(0L);
        usage.setVersion(1);
        usage.setCreatedAt(now);
        usage.setUpdatedAt(now);
        return usage;
    }

    private HyperlinkTaskRoundAccount roundAccount(HyperlinkTask task, HyperlinkTaskRound round,
            HyperlinkTaskAccountUsage usage, AccountHyperlinkCandidateVO account,
            int selectionNo, long now) {
        HyperlinkTaskRoundAccount selected = new HyperlinkTaskRoundAccount();
        selected.setHyperlinkTaskId(task.getId());
        selected.setHyperlinkTaskRoundId(round.getId());
        selected.setRoundNo(round.getRoundNo());
        selected.setTaskAccountUsageId(usage.getId());
        selected.setAccountId(account.accountId());
        selected.setSelectionNo(selectionNo);
        selected.setAssignmentStatus(1);
        selected.setSelectedAt(now);
        selected.setCreatedAt(now);
        selected.setUpdatedAt(now);
        return selected;
    }
}
