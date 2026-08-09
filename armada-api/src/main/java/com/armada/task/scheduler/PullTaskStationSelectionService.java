package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.model.dto.PullTaskStationBinding;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskSelectionMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 为已计划的单次拉人调用选择足量站台，并把选择事实写入角色行。 */
@Service
public class PullTaskStationSelectionService {

    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final AccountProtocolLookupService accountLookup;

    /**
     * @param groupAccountMapper 角色账号 Mapper
     * @param accountLookup      账号域协议身份查询
     */
    public PullTaskStationSelectionService(
            PullTaskGroupAccountMapper groupAccountMapper,
            AccountProtocolLookupService accountLookup) {
        this.groupAccountMapper = groupAccountMapper;
        this.accountLookup = accountLookup;
    }

    /**
     * 选择同群尚未使用的在线站台；数量不足时不写入任何部分选择。
     */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskStationSelection select(
            PullTaskGroupExecution execution,
            PullTaskStandardSetting setting,
            long pullCallId,
            long now) {
        PullTaskStationCandidates candidates = findCandidates(execution, setting);
        if (!candidates.sufficient()) {
            return new PullTaskStationSelection(List.of(), candidates.missingCount());
        }
        return new PullTaskStationSelection(
                bind(execution, pullCallId, candidates.accounts(), now), 0);
    }

    /** 读取已验证候选及缺口，不写角色行；不足时也保留部分候选供补充页排重。 */
    public PullTaskStationCandidates findCandidates(
            PullTaskGroupExecution execution,
            PullTaskStandardSetting setting) {
        return findCandidates(execution, setting, Set.of());
    }

    /** 读取候选，并排除本次已经冻结为料子的号码，避免同一协议请求出现重复 JID。 */
    public PullTaskStationCandidates findCandidates(
            PullTaskGroupExecution execution,
            PullTaskStandardSetting setting,
            Set<String> excludedPhones) {
        return findCandidates(execution, setting, excludedPhones, Set.of());
    }

    /**
     * 为完整波次预检一次调用的站台，并排除已经分配给波次内其他调用的账号。
     */
    public PullTaskStationCandidates findCandidates(
            PullTaskGroupExecution execution,
            PullTaskStandardSetting setting,
            Set<String> excludedPhones,
            Set<Long> excludedAccountIds) {
        int required = setting.getStationCountPerCall() == null
                ? 0 : setting.getStationCountPerCall();
        if (required <= 0) {
            return new PullTaskStationCandidates(List.of(), 0);
        }
        Set<String> excluded = normalizedPhones(excludedPhones);
        Set<Long> excludedIds = excludedAccountIds == null
                ? Set.of() : Set.copyOf(excludedAccountIds);
        List<PullTaskGroupAccount> existing = groupAccountMapper.selectByExecutionAndRole(
                execution.getId(), PullTaskGroupAccountRole.STATION.code());
        Set<Long> usedAccountIds = new HashSet<>();
        existing.stream().map(PullTaskGroupAccount::getAccountId).forEach(usedAccountIds::add);
        usedAccountIds.addAll(excludedIds);
        Set<Long> reusableAccountIds = new HashSet<>();
        existing.stream().filter(PullTaskStationSelectionService::reusableStation)
                .filter(row -> !excludedIds.contains(row.getAccountId()))
                .map(PullTaskGroupAccount::getAccountId).forEach(reusableAccountIds::add);
        LinkedHashMap<Long, ProtocolAccountRef> selected = new LinkedHashMap<>();
        addReusableCandidates(existing, selected, excluded, excludedIds, required);
        List<ProtocolAccountRef> groupCandidates = accountLookup
                .findOnlineNormalByGroupId(setting.getStationGroupId());
        if (groupCandidates != null) {
            for (ProtocolAccountRef account : groupCandidates) {
                if (selected.size() >= required) {
                    break;
                }
                if (!excludedIds.contains(account.armadaAccountId())
                        && eligible(account, excluded)
                        && (reusableAccountIds.contains(account.armadaAccountId())
                            || usedAccountIds.add(account.armadaAccountId()))) {
                    selected.putIfAbsent(account.armadaAccountId(), account);
                }
            }
        }
        List<ProtocolAccountRef> accounts = List.copyOf(selected.values());
        return new PullTaskStationCandidates(
                accounts, Math.max(required - accounts.size(), 0));
    }

    /**
     * 读取已经失败或未知释放、等待再次拉取的站台，不混入新的站台账号。
     *
     * <p>用于料子已经全部收口后的站台独立重试；返回的缺口只针对本次选中的既有
     * 待重试行，避免按每批站台配置继续扩张站台集合。</p>
     */
    public PullTaskStationCandidates findPendingRetryCandidates(
            PullTaskGroupExecution execution,
            PullTaskStandardSetting setting) {
        int limit = setting.getStationCountPerCall() == null
                ? 0 : setting.getStationCountPerCall();
        if (limit <= 0) {
            return new PullTaskStationCandidates(List.of(), 0);
        }
        List<PullTaskGroupAccount> pending = groupAccountMapper.selectPendingStations(
                execution.getId(), limit);
        if (pending.isEmpty()) {
            return new PullTaskStationCandidates(List.of(), 0);
        }
        List<Long> ids = pending.stream().map(PullTaskGroupAccount::getAccountId).toList();
        List<ProtocolAccountRef> active = accountLookup.findActiveProtocolRefs(ids);
        java.util.Map<Long, ProtocolAccountRef> byId = new java.util.HashMap<>();
        if (active != null) {
            active.stream().filter(java.util.Objects::nonNull)
                    .forEach(ref -> byId.putIfAbsent(ref.armadaAccountId(), ref));
        }
        List<ProtocolAccountRef> selected = pending.stream()
                .map(row -> byId.get(row.getAccountId()))
                .filter(java.util.Objects::nonNull)
                .toList();
        return new PullTaskStationCandidates(
                selected, Math.max(pending.size() - selected.size(), 0));
    }

    /** 把已通过足量门禁的候选绑定到指定调用。 */
    public List<PullTaskGroupAccount> bind(
            PullTaskGroupExecution execution,
            long pullCallId,
            List<ProtocolAccountRef> selected,
            long now) {
        List<PullTaskGroupAccount> existing = groupAccountMapper.selectByExecutionAndRole(
                execution.getId(), PullTaskGroupAccountRole.STATION.code());
        java.util.Map<Long, PullTaskGroupAccount> supplements = new java.util.HashMap<>();
        existing.stream().filter(PullTaskStationSelectionService::unassignedSupplement)
                .forEach(row -> supplements.putIfAbsent(row.getAccountId(), row));
        int nextSeq = existing.stream().map(PullTaskGroupAccount::getRoleSeq)
                .filter(value -> value != null).max(Integer::compareTo).orElse(0) + 1;
        List<PullTaskGroupAccount> rows = new ArrayList<>(selected.size());
        for (ProtocolAccountRef account : selected) {
            PullTaskGroupAccount supplement = supplements.get(account.armadaAccountId());
            if (supplement == null) {
                rows.add(insert(execution, account, pullCallId, nextSeq++, now));
            } else {
                rows.add(bindSupplement(supplement, pullCallId, now));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * 为新版逐号码计划准备站台角色行，但不提前写 pull_call_id；活动 attempt 绑定负责占用。
     */
    public List<PullTaskGroupAccount> reserve(
            PullTaskGroupExecution execution,
            List<ProtocolAccountRef> selected,
            long now) {
        List<PullTaskGroupAccount> existing = groupAccountMapper.selectByExecutionAndRole(
                execution.getId(), PullTaskGroupAccountRole.STATION.code());
        java.util.Map<Long, PullTaskGroupAccount> reusable = new java.util.HashMap<>();
        existing.stream().filter(PullTaskStationSelectionService::reusableStation)
                .forEach(row -> reusable.putIfAbsent(row.getAccountId(), row));
        int nextSeq = existing.stream().map(PullTaskGroupAccount::getRoleSeq)
                .filter(value -> value != null).max(Integer::compareTo).orElse(0) + 1;
        List<PullTaskGroupAccount> rows = new ArrayList<>(selected.size());
        for (ProtocolAccountRef account : selected) {
            PullTaskGroupAccount row = reusable.get(account.armadaAccountId());
            rows.add(row == null
                    ? insert(execution, account, null, nextSeq++, now)
                    : row);
        }
        return List.copyOf(rows);
    }

    private PullTaskGroupAccount insert(
            PullTaskGroupExecution execution,
            ProtocolAccountRef account,
            Long pullCallId,
            int roleSeq,
            long now) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(execution.getTaskId());
        row.setGroupExecutionId(execution.getId());
        row.setAccountId(account.armadaAccountId());
        row.setAccountPhone(account.wsPhone());
        row.setRoleType(PullTaskGroupAccountRole.STATION.code());
        row.setRoleSeq(roleSeq);
        row.setSourceType(PullTaskGroupAccountSource.INITIAL.code());
        row.setSelectionMode(PullTaskSelectionMode.AUTOMATIC.code());
        row.setEntryMode(PullTaskAccountEntryMode.PULLER_ADD.code());
        row.setPullCallId(pullCallId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (groupAccountMapper.insert(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("站台角色行写入失败");
        }
        return row;
    }

    private void addReusableCandidates(
            List<PullTaskGroupAccount> existing,
            LinkedHashMap<Long, ProtocolAccountRef> selected,
            Set<String> excludedPhones,
            Set<Long> excludedAccountIds,
            int required) {
        List<PullTaskGroupAccount> rows = existing.stream()
                .filter(PullTaskStationSelectionService::reusableStation)
                .filter(row -> !excludedAccountIds.contains(row.getAccountId()))
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(PullTaskGroupAccount::getAccountId).toList();
        List<ProtocolAccountRef> active = accountLookup.findActiveProtocolRefs(ids);
        if (active == null) {
            return;
        }
        java.util.Map<Long, ProtocolAccountRef> byId = new java.util.HashMap<>();
        active.stream().filter(java.util.Objects::nonNull)
                .forEach(ref -> byId.putIfAbsent(ref.armadaAccountId(), ref));
        for (PullTaskGroupAccount row : rows) {
            if (selected.size() >= required) {
                break;
            }
            ProtocolAccountRef ref = byId.get(row.getAccountId());
            if (eligible(ref, excludedPhones)) {
                selected.putIfAbsent(ref.armadaAccountId(), ref);
            }
        }
    }

    private static boolean eligible(
            ProtocolAccountRef account, Set<String> excludedPhones) {
        if (account == null) {
            return false;
        }
        String phone = normalizedPhone(account.wsPhone());
        return phone != null && !excludedPhones.contains(phone);
    }

    private static Set<String> normalizedPhones(Set<String> phones) {
        if (phones == null || phones.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String phone : phones) {
            String value = normalizedPhone(phone);
            if (value != null) {
                normalized.add(value);
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalizedPhone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String participant = value.trim();
        int suffix = participant.indexOf('@');
        if (suffix >= 0) {
            participant = participant.substring(0, suffix);
        }
        StringBuilder digits = new StringBuilder(participant.length());
        for (int index = 0; index < participant.length(); index++) {
            char current = participant.charAt(index);
            if (Character.isDigit(current)) {
                digits.append(current);
            }
        }
        return digits.isEmpty() ? null : digits.toString();
    }

    private PullTaskGroupAccount bindSupplement(
            PullTaskGroupAccount row, long pullCallId, long now) {
        PullTaskStationBinding binding = new PullTaskStationBinding(
                new PullTaskStationBinding.Scope(row.getId(), pullCallId, now),
                new PullTaskStationBinding.Expected(
                        PullTaskGroupAccountRole.STATION.code(),
                        PullTaskGroupAccountSource.SUPPLEMENT.code(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code()));
        if (groupAccountMapper.bindStationToPullCall(binding) != 1) {
            throw new IllegalStateException("补充站台绑定调用失败");
        }
        row.setPullCallId(pullCallId);
        row.setUpdatedAt(now);
        return row;
    }

    private static boolean unassignedSupplement(PullTaskGroupAccount row) {
        return row.getPullCallId() == null
                && java.util.Objects.equals(row.getSourceType(),
                PullTaskGroupAccountSource.SUPPLEMENT.code())
                && java.util.Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code());
    }

    private static boolean reusableStation(PullTaskGroupAccount row) {
        return row.getPullCallId() == null
                && row.getActivePullAttemptId() == null
                && java.util.Objects.equals(row.getMembershipStatus(),
                        PullTaskGroupAccountMembershipStatus.NOT_JOINED.code())
                && (row.getMembershipFailureCount() == null
                        || row.getMembershipFailureCount() < 4)
                && java.util.Objects.equals(row.getAvailabilityStatus(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code());
    }
}
