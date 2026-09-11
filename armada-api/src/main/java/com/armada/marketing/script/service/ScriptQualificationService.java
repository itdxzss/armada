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
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
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

    /** 启动允许随机匹配；首次发送、未发送任务恢复及手动检查复核全部原绑定，不重抽。 */
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
            var selection = new Selection(accountGroupId, selectedAdmins, original, useBindings);
            Map<String, List<Long>> choices = choices(matchingRoles, facts, selection);
            var assignment = ScriptRoleAssignment.assign(choices);
            var reasons = assignment.isPresent() ? List.<String>of()
                    : assignmentReasons(matchingRoles, steps, facts, selection, choices);
            Map<String, Long> expanded = new LinkedHashMap<>();
            assignment.ifPresent(value -> matchingRoles.forEach((key, messages) -> {
                Long accountId = value.get(key);
                messages.forEach(step -> expanded.put(step.roleKey(), accountId));
                if (!isPromoter(messages.get(0))) selectedAdmins.add(accountId);
            }));
            var report = report(group, facts, selection, required, new Assignment(assignment.isPresent(), reasons));
            reports.add(report);
            if (assignment.isPresent()) bindings.put(group.getGroupLinkId(), Map.copyOf(expanded));
        }
        boolean ready = poolReason == null && !groups.isEmpty() && reports.stream().allMatch(ScriptQualificationVO.Group::ready);
        return new Preparation(new ScriptQualificationVO(ready, count, required, poolReason,
                System.currentTimeMillis(), List.copyOf(reports)), Map.copyOf(bindings));
    }

    /**
     * 发送前只复核当前消息的原账号、群内资格与消息能力，不重新分配账号或检查其他角色。
     * 启动人数门槛不用于运行中的单条发送；不合格时抛业务异常，由执行器记录失败并跳过本条。
     */
    public void requireStepSendable(Long accountGroupId, ScriptMarketingGroup group, ScriptMarketingStepDTO step) {
        var original = content.decodeBindings(group.getBindingsJson());
        Long accountId = original.get(step.roleKey());
        if (accountId == null) throw new BusinessException(ErrorCode.CONFLICT, "角色「" + step.roleKey() + "」未找到原发送账号");
        var facts = candidates.list(List.of(group.getGroupLinkId()), accountGroupId, List.of(accountId)).stream()
                .filter(row -> Objects.equals(row.groupLinkId(), group.getGroupLinkId()))
                .filter(row -> Objects.equals(row.accountId(), accountId)).toList();
        var selection = new Selection(accountGroupId, Set.of(), original, true);
        if (eligibleAccounts(List.of(step), facts, selection).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, unavailableAccountReason(step, facts, selection));
        }
        if (!content.supports(ProtocolBackend.fromProtocolId(facts.get(0).protocolId()), step)) {
            throw new BusinessException(ErrorCode.CONFLICT, "角色「" + step.roleKey() + "」的原发送账号不支持本条消息");
        }
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
            var ids = eligibleAccounts(messages, facts, selection).stream()
                    .filter(row -> messages.stream().allMatch(step -> content.supports(ProtocolBackend.fromProtocolId(row.protocolId()), step)))
                    .map(GroupScriptCandidateVO::accountId).distinct().toList();
            result.put(key, ids);
        });
        return result;
    }

    /** 资格和消息能力分开计算，失败提示复用真实候选，避免把离线误报成消息不支持。 */
    private List<GroupScriptCandidateVO> eligibleAccounts(List<ScriptMarketingStepDTO> messages,
            List<GroupScriptCandidateVO> facts, Selection selection) {
        var role = messages.get(0);
        return facts.stream().filter(ScriptQualificationService::sendable)
                .filter(row -> isPromoter(role) ? promoter(row, selection)
                        : role.accountId() == null ? Boolean.TRUE.equals(row.admin())
                        : Objects.equals(row.accountId(), role.accountId()))
                .filter(row -> !selection.bound() || messages.stream()
                        .allMatch(step -> Objects.equals(row.accountId(), selection.original().get(step.roleKey()))))
                .toList();
    }

    private List<String> assignmentReasons(Map<String, List<ScriptMarketingStepDTO>> roles,
            List<ScriptMarketingStepDTO> steps, List<GroupScriptCandidateVO> facts,
            Selection selection, Map<String, List<Long>> choices) {
        var reasons = new ArrayList<String>();
        var fixedRoles = roles.values().stream().map(messages -> messages.get(0))
                .filter(step -> step.accountId() != null)
                .collect(Collectors.groupingBy(ScriptMarketingStepDTO::accountId, LinkedHashMap::new, Collectors.toList()));
        fixedRoles.forEach((accountId, messages) -> {
            if (messages.size() > 1) reasons.add("角色「" + messages.stream().map(ScriptMarketingStepDTO::roleKey)
                    .collect(Collectors.joining("」和「")) + "」绑定了同一账号（ID：" + accountId + "）。"
                    + (selection.bound() ? "请检查任务的角色账号配置，恢复原绑定后继续。"
                    : "若由同一人发送，请统一角色名称；若需不同人发送，请选择不同账号。"));
        });
        roles.forEach((key, messages) -> {
            if (!choices.get(key).isEmpty()) return;
            var eligible = eligibleAccounts(messages, facts, selection);
            if (!eligible.isEmpty()) reasons.addAll(unsupportedMessageReasons(messages, steps, eligible, selection.bound()));
            else if (!isPromoter(messages.get(0)) || selection.bound()) {
                reasons.add(unavailableAccountReason(messages.get(0), facts, selection));
            }
        });
        if (reasons.isEmpty() && choices.values().stream().noneMatch(List::isEmpty)) {
            reasons.add("角色「" + String.join("」「", roles.keySet())
                    + "」的可用账号重叠，无法为每个角色安排不同账号。请补充支持这些消息的群内账号，或调整角色和消息配置。");
        }
        return reasons;
    }

    private List<String> unsupportedMessageReasons(List<ScriptMarketingStepDTO> messages,
            List<ScriptMarketingStepDTO> steps, List<GroupScriptCandidateVO> eligible, boolean bound) {
        var reasons = new ArrayList<String>();
        for (int index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            if (!messages.contains(step) || eligible.stream()
                    .anyMatch(row -> content.supports(ProtocolBackend.fromProtocolId(row.protocolId()), step))) continue;
            reasons.add("角色「" + step.roleKey() + "」的第 " + (index + 1) + " 条消息无法由"
                    + (bound ? "原绑定账号" : "当前可用账号") + "发送。Android 账号的按钮消息仅支持 1 个「链接跳转」按钮。"
                    + (bound ? "请恢复原账号的消息发送能力；如需修改消息，请新建任务。"
                    : "请修改这条消息的按钮配置，或选择支持该消息的账号。"));
        }
        if (reasons.isEmpty()) reasons.add("角色「" + messages.get(0).roleKey()
                + "」没有能发送其全部消息的账号，请检查该角色的消息配置。");
        return reasons;
    }

    private String unavailableAccountReason(ScriptMarketingStepDTO role,
            List<GroupScriptCandidateVO> facts, Selection selection) {
        Long accountId = selection.bound() ? selection.original().get(role.roleKey()) : role.accountId();
        String label = "角色「" + role.roleKey() + "」的" + (selection.bound() ? "原绑定" : "")
                + (isPromoter(role) ? "账号" : "管理员账号");
        if (accountId == null) return selection.bound()
                ? label + "未记录，请检查任务的角色账号配置。"
                : "本群没有可用管理员。请确认至少一个群管理员或群主已导入系统、在线且在群，再刷新群资料。";
        label += "（ID：" + accountId + "）";
        var account = facts.stream().filter(row -> Objects.equals(row.accountId(), accountId)).findFirst();
        if (account.isEmpty()) return label + "未确认在群，请确认账号已入群并刷新群资料。";
        var row = account.get();
        String stateReason = accountStateReason(row);
        return label + (stateReason.isEmpty() ? roleBindingReason(role, row, selection) : stateReason);
    }

    private String accountStateReason(GroupScriptCandidateVO row) {
        if (!present(row)) return "未确认在群，请确认账号已入群并刷新群资料。";
        if (!Boolean.TRUE.equals(row.online())) return "当前离线或受限，请先恢复账号可用状态，再重新检查。";
        if (Boolean.FALSE.equals(row.groupAvailable())) return "所在的群已不可用，请检查群状态。";
        if (Boolean.FALSE.equals(row.messageSendAllowed())) return "没有群内发言权限，请恢复发言权限后重新检查。";
        if (row.messageSendAllowed() == null) return "的群内发言权限尚未确认，请刷新群资料后重新检查。";
        return "";
    }

    private String roleBindingReason(ScriptMarketingStepDTO role, GroupScriptCandidateVO row, Selection selection) {
        if (!isPromoter(role) && role.accountId() == null && !Boolean.TRUE.equals(row.admin())) {
            return "已不是群管理员或群主，请恢复原账号的管理员权限并刷新群资料。";
        }
        if (isPromoter(role) && !Objects.equals(row.accountGroupId(), selection.accountGroupId())) {
            return "已不在所选推手分组，请恢复原账号的分组后继续。";
        }
        return "与任务保存的角色绑定不一致，请检查原角色账号配置。";
    }

    private ScriptQualificationVO.Group report(ScriptMarketingGroup group, List<GroupScriptCandidateVO> facts,
            Selection selection, int required, Assignment assignment) {
        var pool = facts.stream().filter(r -> promoter(r, selection)).toList();
        int available = (int) pool.stream().filter(ScriptQualificationService::sendable)
                .map(GroupScriptCandidateVO::accountId).distinct().count();
        int offline = (int) pool.stream().filter(r -> present(r) && !Boolean.TRUE.equals(r.online())).count();
        int denied = (int) pool.stream().filter(r -> present(r) && Boolean.FALSE.equals(r.messageSendAllowed())).count();
        int unknown = (int) pool.stream().filter(r -> r.presenceStatus() == null || r.presenceStatus() == 0
                || (present(r) && r.messageSendAllowed() == null)).count();
        var reasons = new ArrayList<>(assignment.reasons());
        int shortage = Math.max(0, required - available);
        if (shortage > 0) reasons.add("需要 " + required + " 个推手，已确认可用 " + available + " 个，缺 " + shortage + " 个");
        if (offline > 0) reasons.add(offline + " 个群内推手离线或受限，请先恢复账号可用状态");
        if (denied > 0) reasons.add(denied + " 个群内推手没有发言权限");
        if (unknown > 0) reasons.add(unknown + " 个账号的成员或发言资格未确认，请刷新群资料");
        boolean groupUnavailable = facts.stream().anyMatch(r -> Boolean.FALSE.equals(r.groupAvailable()));
        if (groupUnavailable) reasons.add("群已不可用，请检查群状态");
        if (shortage > 0 && offline == 0 && denied == 0 && unknown == 0) reasons.add("请到进群任务补齐推手，完成后重新检查");
        return new ScriptQualificationVO.Group(group.getGroupLinkId(), group.getGroupJid(), group.getGroupName(),
                assignment.ready() && !groupUnavailable, required, available, shortage, offline, denied, unknown, List.copyOf(reasons));
    }
    private record Selection(Long accountGroupId, Set<Long> admins, Map<String, Long> original,
            boolean bound) { }
    /** 分配结果与解释同步传递，提示内容不参与是否允许发送的判断。 */
    private record Assignment(boolean ready, List<String> reasons) { }
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
