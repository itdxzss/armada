package com.armada.marketing.export.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.vo.WhatsappGroupDepartedMemberVO;
import com.armada.group.service.WhatsappGroupDepartedMemberService;
import com.armada.marketing.export.mapper.MarketingTaskExportMapper;
import com.armada.marketing.export.model.vo.MarketingTaskCountryEntryExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupMemberExportRow;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** 导出时逐群实时查询 WhatsApp，并与协议已同步的退群事实合并。 */
@Component
public class MarketingTaskWhatsAppMemberProvider {

    private static final int MAX_PARALLEL_ACCOUNTS = 4;
    private static final int MAX_OBSERVER_CANDIDATES = 2;

    private final MarketingTaskExportMapper mapper;
    private final AccountProtocolLookupService accountLookupService;
    private final FixedAccountGroupMetadataPort metadataPort;
    private final WhatsappGroupDepartedMemberService departedMemberService;

    public MarketingTaskWhatsAppMemberProvider(
            MarketingTaskExportMapper mapper,
            AccountProtocolLookupService accountLookupService,
            FixedAccountGroupMetadataPort metadataPort,
            WhatsappGroupDepartedMemberService departedMemberService) {
        this.mapper = mapper;
        this.accountLookupService = accountLookupService;
        this.metadataPort = metadataPort;
        this.departedMemberService = departedMemberService;
    }

    /**
     * 构建全量和按国家模式共用的数据集。
     *
     * @param tenantId 租户 ID
     * @param taskIds 任务 ID
     * @param snapshotAt 作业快照时间
     * @param countryResolver 手机号国家解析器
     * @param ownershipCheck 作业租约检查
     * @return WhatsApp 同源数据集
     */
    public void streamFull(ExportRequest request, FullOutput output) {
        collect(request, ExportSink.full(
                request.countryResolver(), output.groupConsumer(), output.memberConsumer()));
    }

    /** 构建按国家导出所需的 WhatsApp 当前/历史成员数据。 */
    public void streamCountry(
            ExportRequest request,
            Consumer<MarketingTaskCountryEntryExportRow> countryConsumer) {
        collect(request, ExportSink.country(request.countryResolver(), countryConsumer));
    }

    private void collect(ExportRequest request, ExportSink rows) {
        List<MarketingTaskGroupExportRow> groupRows = mapper.selectGroupRowsList(
                request.tenantId(), request.taskIds(), request.snapshotAt());
        if (groupRows == null || groupRows.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "所选营销任务没有可查询的 WhatsApp 群");
        }
        groupRows.forEach(group -> group.setGroupJid(normalizeGroupJid(group.getGroupJid())));
        Map<GroupKey, List<Long>> observerIds = groupObserverIds(groupRows);
        Map<GroupKey, MarketingTaskGroupExportRow> distinctGroups = new LinkedHashMap<>();
        for (MarketingTaskGroupExportRow group : groupRows) {
            distinctGroups.putIfAbsent(new GroupKey(group.getTaskId(), group.getGroupJid()), group);
        }
        List<MarketingTaskGroupExportRow> groups = List.copyOf(distinctGroups.values());
        Map<Long, ProtocolAccountRef> accounts = protocolAccounts(observerIds);
        Map<Long, Object> accountLocks = new HashMap<>();
        accounts.keySet().forEach(id -> accountLocks.put(id, new Object()));
        QueryContext queryContext = new QueryContext(
                observerIds, accounts, accountLocks, request.ownershipCheck());
        queryAndMergeGroups(groups, request.tenantId(), queryContext, rows);
    }

    private void queryAndMergeGroups(
            List<MarketingTaskGroupExportRow> groups,
            Long tenantId,
            QueryContext queryContext,
            ExportSink rows) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL_ACCOUNTS, groups.size()));
        try {
            for (int start = 0; start < groups.size(); start += MAX_PARALLEL_ACCOUNTS) {
                List<MarketingTaskGroupExportRow> batch = groups.subList(
                        start, Math.min(start + MAX_PARALLEL_ACCOUNTS, groups.size()));
                Map<String, List<WhatsappGroupDepartedMemberVO>> departedByGroup = departedByGroup(
                        tenantId, batch.stream().map(MarketingTaskGroupExportRow::getGroupJid).toList());
                List<CompletableFuture<GroupSnapshot>> futures = batch.stream()
                        .map(group -> CompletableFuture.supplyAsync(
                                () -> queryGroup(group, queryContext), executor))
                        .toList();
                for (CompletableFuture<GroupSnapshot> future : futures) {
                    GroupSnapshot snapshot = future.join();
                    rows.addGroup(snapshot.group());
                    mergeGroup(
                            snapshot,
                            departedByGroup.getOrDefault(snapshot.group().getGroupJid(), List.of()),
                            rows);
                }
            }
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof BusinessException businessException) {
                throw businessException;
            }
            throw ex;
        } finally {
            executor.shutdownNow();
        }
    }

    private GroupSnapshot queryGroup(
            MarketingTaskGroupExportRow group,
            QueryContext context) {
        String groupJid = normalizeGroupJid(group.getGroupJid());
        if (groupJid == null) {
            throw groupFailure(group, "缺少群JID");
        }
        List<Long> candidates = context.observerIds()
                .getOrDefault(new GroupKey(group.getTaskId(), groupJid), List.of());
        RuntimeException lastFailure = null;
        int attempted = 0;
        for (Long accountId : candidates) {
            ProtocolAccountRef account = context.accounts().get(accountId);
            if (account == null || attempted >= MAX_OBSERVER_CANDIDATES) {
                continue;
            }
            attempted++;
            try {
                context.ownershipCheck().run();
                GroupMetadataResult metadata;
                synchronized (context.accountLocks().get(accountId)) {
                    context.ownershipCheck().run();
                    metadata = metadataPort.getMetadata(account, groupJid);
                }
                applyLiveMetadata(group, metadata, account);
                return new GroupSnapshot(group, metadata, account);
            } catch (RuntimeException ex) {
                lastFailure = ex;
            }
        }
        String reason = attempted == 0 ? "没有可用的实际发送账号" : "协议查询失败";
        throw groupFailure(group, reason, lastFailure);
    }

    private static void applyLiveMetadata(
            MarketingTaskGroupExportRow group,
            GroupMetadataResult metadata,
            ProtocolAccountRef observer) {
        if (metadata == null || metadata.participants() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "WhatsApp 群详情响应缺少成员列表");
        }
        if (normalize(metadata.subject()) != null) {
            group.setGroupName(metadata.subject().trim());
        }
        group.setGroupMemberCount(metadata.participants().size());
        group.setSpeechPermission(speechPermission(metadata, observer));
    }

    private static String speechPermission(GroupMetadataResult metadata, ProtocolAccountRef observer) {
        if (metadata.announce() == null) {
            return "未确认";
        }
        if (!metadata.announce()) {
            return "所有成员可发言";
        }
        boolean observerAdmin = metadata.participants().stream()
                .filter(participant -> samePhone(participant.phone(), observer.wsPhone()))
                .anyMatch(participant -> Boolean.TRUE.equals(participant.admin())
                        || Boolean.TRUE.equals(participant.owner()));
        return observerAdmin ? "仅管理员可发言（发送账号可发言）" : "无发言权限";
    }

    private static void mergeGroup(
            GroupSnapshot snapshot,
            List<WhatsappGroupDepartedMemberVO> departed,
            ExportSink rows) {
        Set<String> currentIdentities = new LinkedHashSet<>();
        for (GroupParticipantResult participant : snapshot.metadata().participants()) {
            addIdentity(currentIdentities, participant.jid());
            addIdentity(currentIdentities, participant.phone());
            rows.add(snapshot, participant.phone(), participant.jid(),
                    new MemberState(role(participant), true, null, null));
        }
        Map<String, WhatsappGroupDepartedMemberVO> latestDepartures = new LinkedHashMap<>();
        for (WhatsappGroupDepartedMemberVO participant : departed) {
            String departureIdentity = departureIdentity(participant);
            if (departureIdentity != null) {
                latestDepartures.merge(
                        departureIdentity,
                        participant,
                        MarketingTaskWhatsAppMemberProvider::laterDeparture);
            }
        }
        for (WhatsappGroupDepartedMemberVO participant : latestDepartures.values()) {
            if (containsIdentity(currentIdentities, participant.participantJid())
                    || containsIdentity(currentIdentities, participant.phone())) {
                continue;
            }
            rows.add(snapshot, participant.phone(), participant.participantJid(),
                    new MemberState(
                            "历史成员", false, exitType(participant.exitType()), participant.exitedAt()));
        }
    }

    private static String departureIdentity(WhatsappGroupDepartedMemberVO participant) {
        String phoneIdentity = identity(participant.phone());
        return phoneIdentity == null ? identity(participant.participantJid()) : phoneIdentity;
    }

    private static WhatsappGroupDepartedMemberVO laterDeparture(
            WhatsappGroupDepartedMemberVO left,
            WhatsappGroupDepartedMemberVO right) {
        long leftAt = left.exitedAt() == null ? Long.MIN_VALUE : left.exitedAt();
        long rightAt = right.exitedAt() == null ? Long.MIN_VALUE : right.exitedAt();
        return rightAt >= leftAt ? right : left;
    }

    private static MarketingTaskGroupMemberExportRow memberRow(
            MarketingTaskGroupExportRow group,
            String memberValue,
            MemberState state,
            CountryOptionVO country) {
        MarketingTaskGroupMemberExportRow row = new MarketingTaskGroupMemberExportRow();
        row.setTaskId(group.getTaskId());
        row.setTaskName(group.getTaskName());
        row.setGroupName(group.getGroupName());
        row.setGroupLink(group.getGroupLink());
        row.setGroupStatus(group.getGroupStatus());
        row.setGroupMemberCount(group.getGroupMemberCount());
        row.setMemberPhone(memberValue);
        row.setRole(state.role());
        row.setCountryName(country == null ? "未知" : country.nameZh());
        row.setInGroup(state.inGroup() ? "是" : "否");
        row.setExitType(state.exitType() == null ? "" : state.exitType());
        row.setExitedAt(state.exitedAt());
        row.setTaskJoinStatus(state.inGroup() ? "WhatsApp当前群成员" : "WhatsApp历史退群成员");
        return row;
    }

    private static MarketingTaskCountryEntryExportRow countryRow(
            MarketingTaskGroupExportRow group,
            String phone,
            CountryOptionVO country) {
        MarketingTaskCountryEntryExportRow row = new MarketingTaskCountryEntryExportRow();
        row.setTaskId(group.getTaskId());
        row.setTaskName(group.getTaskName());
        row.setCountryName(country.nameZh());
        row.setCountryIso2(country.iso2());
        row.setCountryPhonePrefix(country.phonePrefix());
        row.setActualPhone(phone);
        row.setGroupName(group.getGroupName());
        row.setGroupLink(group.getGroupLink());
        row.setGroupStatus(group.getGroupStatus());
        row.setSpeechPermission(group.getSpeechPermission());
        row.setSenderPhone(group.getSenderPhone());
        row.setMarketingCount(group.getSuccessCount());
        return row;
    }

    private Map<Long, ProtocolAccountRef> protocolAccounts(Map<GroupKey, List<Long>> observerIds) {
        List<Long> requested = observerIds.values().stream().flatMap(List::stream).distinct().toList();
        Map<Long, ProtocolAccountRef> accounts = new LinkedHashMap<>();
        for (ProtocolAccountRef account : accountLookupService.findActiveProtocolRefs(requested)) {
            if (account.backend() == ProtocolBackend.ANDROID) {
                accounts.put(account.armadaAccountId(), account);
            }
        }
        return accounts;
    }

    private static Map<GroupKey, List<Long>> groupObserverIds(List<MarketingTaskGroupExportRow> rows) {
        Map<GroupKey, List<Long>> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }
        for (MarketingTaskGroupExportRow row : rows) {
            String groupJid = normalizeGroupJid(row.getGroupJid());
            if (row.getTaskId() == null || row.getObserverAccountId() == null || groupJid == null) {
                continue;
            }
            result.computeIfAbsent(new GroupKey(row.getTaskId(), groupJid), ignored -> new ArrayList<>())
                    .add(row.getObserverAccountId());
        }
        return result;
    }

    private Map<String, List<WhatsappGroupDepartedMemberVO>> departedByGroup(
            Long tenantId,
            List<String> groupJids) {
        List<String> normalized = groupJids.stream().map(MarketingTaskWhatsAppMemberProvider::normalizeGroupJid)
                .filter(value -> value != null).distinct().sorted().toList();
        Map<String, List<WhatsappGroupDepartedMemberVO>> result = new HashMap<>();
        for (WhatsappGroupDepartedMemberVO row : departedMemberService.findByGroupJids(tenantId, normalized)) {
            result.computeIfAbsent(normalizeGroupJid(row.groupJid()), ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }

    private static String role(GroupParticipantResult participant) {
        if (Boolean.TRUE.equals(participant.owner())) {
            return "群主";
        }
        if (Boolean.TRUE.equals(participant.admin())) {
            return "管理员";
        }
        return "群成员";
    }

    private static String exitType(String value) {
        return "REMOVED".equalsIgnoreCase(value) ? "被移出群" : "主动退群";
    }

    private static boolean samePhone(String left, String right) {
        return digits(left).equals(digits(right)) && !digits(left).isEmpty();
    }

    private static String identity(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        int separator = lower.indexOf('@');
        if (separator < 0) {
            String phone = digits(lower);
            return phone.isEmpty() ? "value:" + lower : "phone:" + phone;
        }
        String user = lower.substring(0, separator);
        String server = lower.substring(separator + 1);
        int deviceSeparator = user.indexOf(':');
        if (deviceSeparator >= 0) {
            user = user.substring(0, deviceSeparator);
        }
        if ("s.whatsapp.net".equals(server) && user.chars().allMatch(Character::isDigit)) {
            return "phone:" + user;
        }
        return "jid:" + user + "@" + server;
    }

    private static void addIdentity(Set<String> identities, String value) {
        String identity = identity(value);
        if (identity != null) {
            identities.add(identity);
        }
    }

    private static boolean containsIdentity(Set<String> identities, String value) {
        String identity = identity(value);
        return identity != null && identities.contains(identity);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeGroupJid(String value) {
        String normalized = normalize(value);
        if (normalized == null || normalized.contains("@")) {
            return normalized;
        }
        return normalized + "@g.us";
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private static String effectivePhone(String explicitPhone, String jid) {
        String normalizedPhone = normalize(explicitPhone);
        if (normalizedPhone != null) {
            String phoneDigits = digits(normalizedPhone);
            return phoneDigits.isEmpty() ? null : phoneDigits;
        }
        String normalizedJid = normalize(jid);
        if (normalizedJid == null) {
            return null;
        }
        String lower = normalizedJid.toLowerCase(Locale.ROOT);
        int at = lower.indexOf('@');
        if (at <= 0 || !"s.whatsapp.net".equals(lower.substring(at + 1))) {
            return null;
        }
        String user = lower.substring(0, at);
        int device = user.indexOf(':');
        if (device >= 0) {
            user = user.substring(0, device);
        }
        return user.length() >= 5 && user.length() <= 20
                && user.chars().allMatch(Character::isDigit) ? user : null;
    }

    private static BusinessException groupFailure(MarketingTaskGroupExportRow group, String reason) {
        return groupFailure(group, reason, null);
    }

    private static BusinessException groupFailure(
            MarketingTaskGroupExportRow group,
            String reason,
            RuntimeException cause) {
        String message = "WhatsApp群查询失败: taskId=" + group.getTaskId()
                + ", group=" + maskedGroup(group.getGroupJid()) + ", reason=" + reason;
        if (cause != null) {
            message += " (" + cause.getClass().getSimpleName() + ")";
        }
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private static String maskedGroup(String groupJid) {
        String normalized = normalize(groupJid);
        if (normalized == null || normalized.length() <= 8) {
            return "***";
        }
        return normalized.substring(0, 4) + "***" + normalized.substring(normalized.length() - 4);
    }

    private record GroupKey(Long taskId, String groupJid) {
    }

    /** 单次导出查询上下文。 */
    public record ExportRequest(
            Long tenantId,
            List<Long> taskIds,
            long snapshotAt,
            CountryService.PhonePrefixResolver countryResolver,
            Runnable ownershipCheck) {
    }

    /** 全量导出的双工作表逐行输出边界。 */
    public record FullOutput(
            Consumer<MarketingTaskGroupExportRow> groupConsumer,
            Consumer<MarketingTaskGroupMemberExportRow> memberConsumer) {
    }

    private record QueryContext(
            Map<GroupKey, List<Long>> observerIds,
            Map<Long, ProtocolAccountRef> accounts,
            Map<Long, Object> accountLocks,
            Runnable ownershipCheck) {
    }

    private record MemberState(String role, boolean inGroup, String exitType, Long exitedAt) {
    }

    private static final class ExportSink {

        private final CountryService.PhonePrefixResolver countryResolver;
        private final Consumer<MarketingTaskGroupExportRow> groupConsumer;
        private final Consumer<MarketingTaskGroupMemberExportRow> memberConsumer;
        private final Consumer<MarketingTaskCountryEntryExportRow> countryConsumer;

        private ExportSink(
                CountryService.PhonePrefixResolver countryResolver,
                Consumer<MarketingTaskGroupExportRow> groupConsumer,
                Consumer<MarketingTaskGroupMemberExportRow> memberConsumer,
                Consumer<MarketingTaskCountryEntryExportRow> countryConsumer) {
            this.countryResolver = countryResolver;
            this.groupConsumer = groupConsumer;
            this.memberConsumer = memberConsumer;
            this.countryConsumer = countryConsumer;
        }

        private static ExportSink full(
                CountryService.PhonePrefixResolver countryResolver,
                Consumer<MarketingTaskGroupExportRow> groupConsumer,
                Consumer<MarketingTaskGroupMemberExportRow> memberConsumer) {
            return new ExportSink(countryResolver, groupConsumer, memberConsumer, null);
        }

        private static ExportSink country(
                CountryService.PhonePrefixResolver countryResolver,
                Consumer<MarketingTaskCountryEntryExportRow> countryConsumer) {
            return new ExportSink(countryResolver, null, null, countryConsumer);
        }

        private void addGroup(MarketingTaskGroupExportRow group) {
            if (groupConsumer != null) {
                groupConsumer.accept(group);
            }
        }

        private void add(GroupSnapshot snapshot, String phone, String jid, MemberState state) {
            String effectivePhone = effectivePhone(phone, jid);
            String memberValue = effectivePhone == null ? jid : effectivePhone;
            CountryOptionVO country = effectivePhone == null ? null : countryResolver.resolve(effectivePhone);
            if (memberConsumer != null) {
                memberConsumer.accept(memberRow(snapshot.group(), memberValue, state, country));
            }
            if (countryConsumer != null && country != null) {
                countryConsumer.accept(countryRow(snapshot.group(), effectivePhone, country));
            }
        }
    }

    private record GroupSnapshot(
            MarketingTaskGroupExportRow group,
            GroupMetadataResult metadata,
            ProtocolAccountRef observer) {
    }
}
