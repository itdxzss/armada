package com.armada.marketing.script.service;

import com.armada.account.model.dto.AccountQuery;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountService;
import com.armada.group.model.vo.GroupScriptCandidateVO;
import com.armada.group.service.GroupScriptCandidateService;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.model.vo.ScriptQualificationVO;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 逐群资格校验和完整随机匹配；只有整个报告通过，执行器才能固定绑定并启动。 */
@Service
public class ScriptQualificationService {
    private final AccountGroupService accountGroups;
    private final AccountService accounts;
    private final GroupScriptCandidateService candidates;
    private final ScriptMarketingContentService content;
    /** 复用账号与群的跨域只读服务。 */
    public ScriptQualificationService(AccountGroupService accountGroups, AccountService accounts,
            GroupScriptCandidateService candidates, ScriptMarketingContentService content) {
        this.accountGroups = accountGroups; this.accounts = accounts;
        this.candidates = candidates; this.content = content;
    }
    /** 完整检查与匹配结果；按群链接 ID 保存，检查本身不落库或发送。 */
    public record Preparation(ScriptQualificationVO report, Map<Long, Map<String, Long>> bindings) { }

    /** 首次启动允许随机匹配；定时首次发送、恢复及运行中复核原绑定，不重抽。 */
    public Preparation inspect(Long accountGroupId, List<ScriptMarketingStepDTO> steps,
            List<ScriptMarketingGroup> groups, boolean useBindings) {
        accountGroups.requireExisting(accountGroupId);
        var query = new AccountQuery(); query.setAccountGroupId(accountGroupId); query.setPageSize(1);
        long count = accounts.listAccounts(query).total();
        var roles = steps.stream().collect(Collectors.groupingBy(ScriptMarketingStepDTO::roleKey,
                LinkedHashMap::new, Collectors.toList()));
        var matchingRoles = mergeAutomaticAdmins(roles);
        int required = (int) roles.values().stream().filter(v -> isPromoter(v.get(0))).count();
        String poolReason = count > required ? null : "推手分组需要至少 " + (required + 1) + " 个账号，当前 " + count + " 个";
        var admins = steps.stream().filter(s -> !isPromoter(s)).map(ScriptMarketingStepDTO::accountId)
                .filter(Objects::nonNull).distinct().toList();
        // 原绑定管理员即使被撤权或离群也要读取，复核时不能悄悄换成其他管理员。
        var lookupAdmins = new HashSet<>(admins);
        if (useBindings) groups.forEach(group -> {
            var original = content.decodeBindings(group.getBindingsJson());
            steps.stream().filter(s -> !isPromoter(s)).map(s -> original.get(s.roleKey()))
                    .filter(Objects::nonNull).forEach(lookupAdmins::add);
        });
        var rows = candidates.list(groups.stream().map(ScriptMarketingGroup::getGroupLinkId).toList(), accountGroupId, List.copyOf(lookupAdmins));
        var byGroup = rows.stream().collect(Collectors.groupingBy(GroupScriptCandidateVO::groupLinkId));
        var reports = new ArrayList<ScriptQualificationVO.Group>();
        Map<Long, Map<String, Long>> bindings = new LinkedHashMap<>();
        for (var group : groups) {
            var facts = byGroup.getOrDefault(group.getGroupLinkId(), List.of());
            var original = useBindings ? content.decodeBindings(group.getBindingsJson()) : Map.<String, Long>of();
            var selectedAdmins = new HashSet<>(admins);
            if (useBindings) steps.stream().filter(s -> !isPromoter(s)).map(s -> original.get(s.roleKey()))
                    .filter(Objects::nonNull).forEach(selectedAdmins::add);
            Map<String, List<Long>> choices = choices(matchingRoles, facts,
                    new Selection(accountGroupId, selectedAdmins, original, useBindings, false));
            var assignment = ScriptRoleAssignment.assign(choices);
            boolean adminUnavailable = matchingRoles.entrySet().stream()
                    .anyMatch(entry -> !isPromoter(entry.getValue().get(0)) && choices.get(entry.getKey()).isEmpty());
            Map<String, Long> expanded = new LinkedHashMap<>();
            assignment.ifPresent(value -> matchingRoles.forEach((key, messages) -> {
                Long accountId = value.get(key);
                messages.forEach(step -> expanded.put(step.roleKey(), accountId));
                if (!isPromoter(messages.get(0))) selectedAdmins.add(accountId);
            }));
            var report = report(group, facts, new Selection(accountGroupId, selectedAdmins, original, useBindings, adminUnavailable),
                    required, assignment.isPresent());
            reports.add(report);
            if (assignment.isPresent()) bindings.put(group.getGroupLinkId(), Map.copyOf(expanded));
        }
        boolean ready = poolReason == null && !groups.isEmpty() && reports.stream().allMatch(ScriptQualificationVO.Group::ready);
        return new Preparation(new ScriptQualificationVO(ready, count, required, poolReason,
                System.currentTimeMillis(), List.copyOf(reports)), Map.copyOf(bindings));
    }

    /** 自动管理员的不同角色名合并参与匹配，保证同群只选一人且支持全部管理员消息。 */
    private Map<String, List<ScriptMarketingStepDTO>> mergeAutomaticAdmins(Map<String, List<ScriptMarketingStepDTO>> roles) {
        Map<String, List<ScriptMarketingStepDTO>> result = new LinkedHashMap<>();
        var automatic = roles.values().stream().flatMap(List::stream)
                .filter(step -> !isPromoter(step) && step.accountId() == null).toList();
        roles.forEach((key, messages) -> {
            var role = messages.get(0);
            if (isPromoter(role) || role.accountId() != null) result.put(key, messages);
            else if (key.equals(automatic.get(0).roleKey())) result.put(key, automatic);
        });
        return result;
    }

    private Map<String, List<Long>> choices(Map<String, List<ScriptMarketingStepDTO>> roles,
            List<GroupScriptCandidateVO> facts, Selection selection) {
        Map<String, List<Long>> result = new LinkedHashMap<>();
        roles.forEach((key, messages) -> {
            var role = messages.get(0);
            var ids = facts.stream().filter(ScriptQualificationService::sendable)
                    .filter(row -> isPromoter(role) ? promoter(row, selection)
                            : role.accountId() == null ? Boolean.TRUE.equals(row.admin())
                            : Objects.equals(row.accountId(), role.accountId()))
                    .filter(row -> !selection.bound() || messages.stream()
                            .allMatch(step -> Objects.equals(row.accountId(), selection.original().get(step.roleKey()))))
                    .filter(row -> messages.stream().allMatch(step -> content.supports(ProtocolBackend.fromProtocolId(row.protocolId()), step)))
                    .map(GroupScriptCandidateVO::accountId).distinct().toList();
            result.put(key, ids);
        });
        return result;
    }
    private ScriptQualificationVO.Group report(ScriptMarketingGroup group, List<GroupScriptCandidateVO> facts,
            Selection selection, int required, boolean assigned) {
        var pool = facts.stream().filter(r -> promoter(r, selection)).toList();
        int available = (int) pool.stream().filter(ScriptQualificationService::sendable)
                .map(GroupScriptCandidateVO::accountId).distinct().count();
        int offline = (int) pool.stream().filter(r -> present(r) && !Boolean.TRUE.equals(r.online())).count();
        int denied = (int) pool.stream().filter(r -> present(r) && Boolean.FALSE.equals(r.messageSendAllowed())).count();
        int unknown = (int) pool.stream().filter(r -> r.presenceStatus() == null || r.presenceStatus() == 0
                || (present(r) && r.messageSendAllowed() == null)).count();
        var reasons = new ArrayList<String>();
        if (selection.adminUnavailable()) reasons.add(selection.bound()
                ? "原绑定管理员已离线、退群、失去管理员权限或无法发送剧本消息，请恢复原账号后继续"
                : "无可用在控管理员：需有在线、在群且支持剧本消息的管理员或群主，请刷新群资料并检查账号状态");
        int shortage = Math.max(0, required - available);
        if (shortage > 0) reasons.add("需要 " + required + " 个推手，已确认可用 " + available + " 个，缺 " + shortage + " 个");
        if (offline > 0) reasons.add(offline + " 个群内推手离线或受限，请先恢复账号可用状态");
        if (denied > 0) reasons.add(denied + " 个群内推手没有发言权限");
        if (unknown > 0) reasons.add(unknown + " 个账号的成员或发言资格未确认，请刷新群资料");
        if (!assigned && shortage == 0 && !selection.adminUnavailable()) reasons.add(selection.bound()
                ? "原绑定账号资格发生变化，请恢复原账号后继续"
                : "管理员与推手无法分配不同的可用账号，或角色消息的协议能力不满足要求，请检查群内账号和消息配置");
        boolean groupUnavailable = facts.stream().anyMatch(r -> Boolean.FALSE.equals(r.groupAvailable()));
        if (groupUnavailable) reasons.add("群已不可用，请检查群状态");
        if (shortage > 0 && offline == 0 && denied == 0 && unknown == 0) reasons.add("请到进群任务补齐推手，完成后重新检查");
        return new ScriptQualificationVO.Group(group.getGroupLinkId(), group.getGroupJid(), group.getGroupName(),
                assigned && !groupUnavailable, required, available, shortage, offline, denied, unknown, List.copyOf(reasons));
    }
    private record Selection(Long accountGroupId, Set<Long> admins, Map<String, Long> original,
            boolean bound, boolean adminUnavailable) { }
    private static boolean isPromoter(ScriptMarketingStepDTO step) { return "PROMOTER".equals(step.role()); }
    private static boolean promoter(GroupScriptCandidateVO row, Selection selection) {
        return Objects.equals(row.accountGroupId(), selection.accountGroupId()) && !selection.admins().contains(row.accountId());
    }
    private static boolean present(GroupScriptCandidateVO row) { return Integer.valueOf(1).equals(row.presenceStatus()); }
    private static boolean sendable(GroupScriptCandidateVO row) {
        return present(row) && Boolean.TRUE.equals(row.online()) && Boolean.TRUE.equals(row.messageSendAllowed())
                && !Boolean.FALSE.equals(row.groupAvailable());
    }
}
