package com.armada.marketing.export.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.vo.WhatsappGroupDepartedMemberVO;
import com.armada.group.model.vo.WhatsappGroupMemberCacheSnapshotVO;
import com.armada.group.model.vo.WhatsappGroupMemberStateVO;
import com.armada.group.model.vo.WhatsappGroupJoinFactVO;
import com.armada.group.service.WhatsappGroupDepartedMemberService;
import com.armada.group.service.WhatsappGroupMemberCacheService;
import com.armada.group.service.WhatsappGroupMemberJoinFactService;
import com.armada.marketing.export.mapper.MarketingTaskExportMapper;
import com.armada.marketing.export.model.vo.MarketingTaskCountryEntryExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupExportRow;
import com.armada.marketing.export.model.vo.MarketingTaskGroupMemberExportRow;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 营销导出优先读库；快照不完整时尝试协议补齐，失败则降级使用库内现有事实。 */
@Component
public class MarketingTaskWhatsAppMemberProvider {

    private static final Logger log = LoggerFactory.getLogger(MarketingTaskWhatsAppMemberProvider.class);
    private static final int MAX_PARALLEL_GROUPS = 4;
    private static final int MAX_OBSERVER_CANDIDATES = 2;
    private static final String INVITE_URL_PREFIX = "https://chat.whatsapp.com/";
    private static final String INVITE_UNAVAILABLE = "无权限获取";

    private final MarketingTaskExportMapper mapper;
    private final AccountProtocolLookupService accountLookupService;
    private final FixedAccountGroupMetadataPort metadataPort;
    private final WhatsappGroupMemberCacheService memberCacheService;
    private final WhatsappGroupDepartedMemberService departedMemberService;
    private final WhatsappGroupMemberJoinFactService joinFactService;

    public MarketingTaskWhatsAppMemberProvider(
            MarketingTaskExportMapper mapper,
            AccountProtocolLookupService accountLookupService,
            FixedAccountGroupMetadataPort metadataPort,
            WhatsappGroupMemberCacheService memberCacheService,
            WhatsappGroupDepartedMemberService departedMemberService,
            WhatsappGroupMemberJoinFactService joinFactService) {
        this.mapper = mapper;
        this.accountLookupService = accountLookupService;
        this.metadataPort = metadataPort;
        this.memberCacheService = memberCacheService;
        this.departedMemberService = departedMemberService;
        this.joinFactService = joinFactService;
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
        groupRows.forEach(group -> {
            group.setGroupJid(normalizeGroupJid(group.getGroupJid()));
            String storedInvite = standardInviteUrl(group.getGroupLink());
            group.setGroupLink(storedInvite == null ? INVITE_UNAVAILABLE : storedInvite);
        });
        Map<GroupKey, List<Long>> observerIds = groupObserverIds(groupRows);
        Map<GroupKey, MarketingTaskGroupExportRow> distinctGroups = new LinkedHashMap<>();
        for (MarketingTaskGroupExportRow group : groupRows) {
            distinctGroups.putIfAbsent(new GroupKey(group.getTaskId(), group.getGroupJid()), group);
        }
        List<MarketingTaskGroupExportRow> groups = List.copyOf(distinctGroups.values());
        Map<String, WhatsappGroupMemberCacheSnapshotVO> cachedByGroup = memberCacheService.findByGroupJids(
                request.tenantId(), groups.stream()
                        .map(MarketingTaskGroupExportRow::getGroupJid)
                        .distinct()
                        .toList());
        Map<GroupKey, List<Long>> incompleteObserverIds = new LinkedHashMap<>();
        observerIds.forEach((key, value) -> {
            if (!isCompleteSnapshot(cachedByGroup.get(key.groupJid()))) {
                incompleteObserverIds.put(key, value);
            }
        });
        Map<Long, ProtocolAccountRef> accounts = protocolAccounts(incompleteObserverIds);
        Map<Long, Object> accountLocks = new HashMap<>();
        accounts.keySet().forEach(id -> accountLocks.put(id, new Object()));
        QueryContext queryContext = new QueryContext(
                Map.copyOf(incompleteObserverIds), accounts, accountLocks, request.ownershipCheck());
        queryAndMergeGroups(
                groups, request.tenantId(), request.snapshotAt(), cachedByGroup,
                queryContext, rows);
    }

    private void queryAndMergeGroups(
            List<MarketingTaskGroupExportRow> groups,
            Long tenantId,
            long snapshotAt,
            Map<String, WhatsappGroupMemberCacheSnapshotVO> cachedByGroup,
            QueryContext queryContext,
            ExportSink rows) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL_GROUPS, groups.size()));
        try {
            for (int start = 0; start < groups.size(); start += MAX_PARALLEL_GROUPS) {
                List<MarketingTaskGroupExportRow> batch = groups.subList(
                        start, Math.min(start + MAX_PARALLEL_GROUPS, groups.size()));
                List<String> batchGroupJids = batch.stream()
                        .map(MarketingTaskGroupExportRow::getGroupJid)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
                Map<String, List<WhatsappGroupDepartedMemberVO>> departedByGroup = departedByGroup(
                        tenantId, batchGroupJids);
                Map<String, List<WhatsappGroupJoinFactVO>> joinsByGroup = joinsByGroup(
                        tenantId, batchGroupJids);
                List<CompletableFuture<GroupSnapshot>> futures = batch.stream()
                        .map(group -> CompletableFuture.supplyAsync(
                                () -> queryGroup(
                                        group, cachedByGroup.get(group.getGroupJid()),
                                        tenantId, snapshotAt, queryContext), executor))
                        .toList();
                for (CompletableFuture<GroupSnapshot> future : futures) {
                    GroupSnapshot snapshot = future.join();
                    List<WhatsappGroupJoinFactVO> joins = joinsByGroup.getOrDefault(
                            snapshot.group().getGroupJid(), List.of());
                    applyJoinedPhoneCount(snapshot.group(), joins, snapshotAt);
                    rows.addGroup(snapshot.group());
                    mergeGroup(
                            snapshot,
                            departedByGroup.getOrDefault(snapshot.group().getGroupJid(), List.of()),
                            joins,
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
            WhatsappGroupMemberCacheSnapshotVO cached,
            Long tenantId,
            long snapshotAt,
            QueryContext context) {
        String groupJid = normalizeGroupJid(group.getGroupJid());
        if (groupJid == null) {
            log.warn("营销导出群缺少JID，按数据库摘要继续 taskId={}", group.getTaskId());
            return fallbackSnapshot(group, null, cached);
        }
        if (isCompleteSnapshot(cached)) {
            applyCachedMetadata(group, cached, group.getSenderPhone());
            return new GroupSnapshot(group, cached);
        }

        List<Long> candidates = context.observerIds()
                .getOrDefault(new GroupKey(group.getTaskId(), groupJid), List.of());
        int attempted = 0;
        RuntimeException lastFailure = null;
        for (Long accountId : candidates) {
            ProtocolAccountRef account = context.accounts().get(accountId);
            if (account == null || attempted >= MAX_OBSERVER_CANDIDATES) {
                continue;
            }
            attempted++;
            context.ownershipCheck().run();
            try {
                GroupMetadataResult metadata;
                synchronized (context.accountLocks().get(accountId)) {
                    metadata = metadataPort.getMetadata(account, groupJid);
                }
                validateCompleteMetadata(metadata);
                WhatsappGroupMemberCacheSnapshotVO fresh = memberCacheService.replaceCompleteSnapshot(
                        tenantId, accountId, groupJid, metadata, snapshotAt);
                if (fresh == null) {
                    throw new IllegalStateException("群成员完整快照写入结果为空");
                }
                applyCachedMetadata(group, fresh, account.wsPhone());
                return new GroupSnapshot(group, fresh);
            } catch (RuntimeException ex) {
                lastFailure = ex;
                log.warn("营销导出协议补齐失败，降级候选 taskId={} groupJid={} accountId={} "
                                + "attempt={} exceptionType={}",
                        group.getTaskId(), maskedGroup(groupJid), accountId, attempted,
                        ex.getClass().getSimpleName());
            }
        }
        log.info("营销导出使用数据库降级数据 taskId={} groupJid={} hasCachedFacts={} "
                        + "candidateCount={} attempted={} lastExceptionType={}",
                group.getTaskId(), maskedGroup(groupJid), cached != null, candidates.size(), attempted,
                lastFailure == null ? null : lastFailure.getClass().getSimpleName());
        return fallbackSnapshot(group, groupJid, cached);
    }

    private static boolean isCompleteSnapshot(WhatsappGroupMemberCacheSnapshotVO cached) {
        return cached != null && cached.snapshotAt() != null;
    }

    private static void validateCompleteMetadata(GroupMetadataResult metadata) {
        if (metadata == null || metadata.participants() == null || !metadata.participantsComplete()) {
            throw new IllegalStateException("协议返回的群成员快照不完整");
        }
    }

    private static GroupSnapshot fallbackSnapshot(
            MarketingTaskGroupExportRow group,
            String groupJid,
            WhatsappGroupMemberCacheSnapshotVO cached) {
        if (cached != null) {
            applyCachedMetadata(group, cached, group.getSenderPhone());
            return new GroupSnapshot(group, cached);
        }
        WhatsappGroupMemberCacheSnapshotVO empty = new WhatsappGroupMemberCacheSnapshotVO(
                groupJid, null, null, null, null, List.of());
        if (group.getGroupMemberCount() == null) {
            group.setGroupMemberCount(0);
        }
        if (normalize(group.getSpeechPermission()) == null) {
            group.setSpeechPermission("未确认");
        }
        return new GroupSnapshot(group, empty);
    }

    private static void applyCachedMetadata(
            MarketingTaskGroupExportRow group,
            WhatsappGroupMemberCacheSnapshotVO cached,
            String observerPhone) {
        if (normalize(cached.subject()) != null) {
            group.setGroupName(cached.subject().trim());
        }
        group.setGroupMemberCount(Math.toIntExact(
                cached.members().stream().filter(WhatsappGroupMemberStateVO::inGroup).count()));
        if (cached.announce() != null) {
            group.setSpeechPermission(speechPermission(cached, observerPhone));
        } else if (normalize(group.getSpeechPermission()) == null) {
            group.setSpeechPermission("未确认");
        }
    }

    private static String speechPermission(
            WhatsappGroupMemberCacheSnapshotVO cached,
            String observerPhone) {
        if (cached.announce() == null) {
            return "未确认";
        }
        if (!cached.announce()) {
            return "所有成员可发言";
        }
        boolean observerAdmin = cached.members().stream()
                .filter(WhatsappGroupMemberStateVO::inGroup)
                .filter(participant -> samePhone(participant.phone(), observerPhone))
                .anyMatch(participant -> Boolean.TRUE.equals(participant.admin())
                        || Boolean.TRUE.equals(participant.owner()));
        return observerAdmin ? "仅管理员可发言（发送账号可发言）" : "无发言权限";
    }

    private static void mergeGroup(
            GroupSnapshot snapshot,
            List<WhatsappGroupDepartedMemberVO> departed,
            List<WhatsappGroupJoinFactVO> joins,
            ExportSink rows) {
        Map<String, WhatsappGroupJoinFactVO> latestJoins = latestJoins(joins);
        Map<String, WhatsappGroupDepartedMemberVO> latestDepartures = latestDepartures(departed);
        Set<String> cachedIdentities = new LinkedHashSet<>();
        for (WhatsappGroupMemberStateVO participant : snapshot.cached().members()) {
            addIdentity(cachedIdentities, participant.participantJid());
            addIdentity(cachedIdentities, participant.phone());
            WhatsappGroupJoinFactVO join = joinFor(
                    latestJoins, participant.phone(), participant.participantJid());
            WhatsappGroupDepartedMemberVO departure = departureFor(
                    latestDepartures, participant.phone(), participant.participantJid());
            boolean inGroup = participant.inGroup();
            rows.add(snapshot, participant.phone(), participant.participantJid(),
                    new MemberState(
                            role(participant), inGroup,
                            inGroup || departure == null ? null : exitType(departure),
                            join == null ? null : join.joinedAt(),
                            inGroup || departure == null ? null : departure.exitedAt()));
        }
        for (WhatsappGroupDepartedMemberVO participant : new LinkedHashSet<>(latestDepartures.values())) {
            if (containsIdentity(cachedIdentities, participant.participantJid())
                    || containsIdentity(cachedIdentities, participant.phone())) {
                continue;
            }
            WhatsappGroupJoinFactVO join = joinFor(
                    latestJoins, participant.phone(), participant.participantJid());
            rows.add(snapshot, participant.phone(), participant.participantJid(),
                    new MemberState(
                            "历史成员", false, exitType(participant),
                            join == null ? null : join.joinedAt(), participant.exitedAt()));
        }
    }

    private static Map<String, WhatsappGroupJoinFactVO> latestJoins(List<WhatsappGroupJoinFactVO> joins) {
        Map<String, WhatsappGroupJoinFactVO> result = new LinkedHashMap<>();
        for (WhatsappGroupJoinFactVO join : joins) {
            String phoneIdentity = identity(join.phone());
            String jidIdentity = identity(join.participantJid());
            if (phoneIdentity != null) {
                result.merge(phoneIdentity, join, MarketingTaskWhatsAppMemberProvider::laterJoin);
            }
            if (jidIdentity != null) {
                result.merge(jidIdentity, join, MarketingTaskWhatsAppMemberProvider::laterJoin);
            }
        }
        return result;
    }

    private static WhatsappGroupJoinFactVO joinFor(
            Map<String, WhatsappGroupJoinFactVO> joins,
            String phone,
            String jid) {
        WhatsappGroupJoinFactVO byPhone = joins.get(identity(phone));
        return byPhone == null ? joins.get(identity(jid)) : byPhone;
    }

    private static WhatsappGroupJoinFactVO laterJoin(
            WhatsappGroupJoinFactVO left,
            WhatsappGroupJoinFactVO right) {
        long leftAt = left.joinedAt() == null ? Long.MIN_VALUE : left.joinedAt();
        long rightAt = right.joinedAt() == null ? Long.MIN_VALUE : right.joinedAt();
        return rightAt >= leftAt ? right : left;
    }

    private static void applyJoinedPhoneCount(
            MarketingTaskGroupExportRow group,
            List<WhatsappGroupJoinFactVO> joins,
            long snapshotAt) {
        long lowerBound = group.getJoinedTaskAt() == null ? Long.MIN_VALUE : group.getJoinedTaskAt();
        long count = joins.stream()
                .filter(join -> join.joinedAt() != null
                        && join.joinedAt() >= lowerBound
                        && join.joinedAt() <= snapshotAt)
                .map(join -> effectivePhone(join.phone(), join.participantJid()))
                .filter(phone -> phone != null)
                .distinct()
                .count();
        group.setJoinedPhoneCount(Math.toIntExact(count));
    }

    private static Map<String, WhatsappGroupDepartedMemberVO> latestDepartures(
            List<WhatsappGroupDepartedMemberVO> departed) {
        Map<String, WhatsappGroupDepartedMemberVO> result = new LinkedHashMap<>();
        for (WhatsappGroupDepartedMemberVO participant : departed) {
            String phoneIdentity = identity(participant.phone());
            String jidIdentity = identity(participant.participantJid());
            if (phoneIdentity != null) {
                result.merge(phoneIdentity, participant, MarketingTaskWhatsAppMemberProvider::laterDeparture);
            }
            if (jidIdentity != null) {
                result.merge(jidIdentity, participant, MarketingTaskWhatsAppMemberProvider::laterDeparture);
            }
        }
        return result;
    }

    private static WhatsappGroupDepartedMemberVO departureFor(
            Map<String, WhatsappGroupDepartedMemberVO> departures,
            String phone,
            String jid) {
        WhatsappGroupDepartedMemberVO byPhone = departures.get(identity(phone));
        return byPhone == null ? departures.get(identity(jid)) : byPhone;
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
        row.setJoinedAt(state.joinedAt());
        row.setExitedAt(state.exitedAt());
        row.setTaskJoinStatus(state.inGroup() ? "WhatsApp当前群成员" : "WhatsApp历史退群成员");
        return row;
    }

    private static MarketingTaskCountryEntryExportRow countryRow(
            MarketingTaskGroupExportRow group,
            String phone,
            CountryOptionVO country,
            Long joinedAt) {
        MarketingTaskCountryEntryExportRow row = new MarketingTaskCountryEntryExportRow();
        row.setJoinedAt(joinedAt);
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
        row.setJoinedPhoneCount(group.getJoinedPhoneCount());
        row.setMarketingCount(group.getSuccessCount());
        return row;
    }

    private Map<Long, ProtocolAccountRef> protocolAccounts(Map<GroupKey, List<Long>> observerIds) {
        List<Long> requested = observerIds.values().stream().flatMap(List::stream).distinct().toList();
        if (requested.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, ProtocolAccountRef> accounts = new LinkedHashMap<>();
            for (ProtocolAccountRef account : accountLookupService.findActiveProtocolRefs(requested)) {
                accounts.put(account.armadaAccountId(), account);
            }
            return Map.copyOf(accounts);
        } catch (RuntimeException ex) {
            log.warn("营销导出查询在线协议账号失败，全部降级数据库 requestedCount={} exceptionType={}",
                    requested.size(), ex.getClass().getSimpleName());
            return Map.of();
        }
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

    private Map<String, List<WhatsappGroupJoinFactVO>> joinsByGroup(
            Long tenantId,
            List<String> groupJids) {
        List<String> normalized = groupJids.stream()
                .map(MarketingTaskWhatsAppMemberProvider::normalizeGroupJid)
                .filter(value -> value != null)
                .distinct()
                .sorted()
                .toList();
        Map<String, List<WhatsappGroupJoinFactVO>> result = new HashMap<>();
        for (WhatsappGroupJoinFactVO row : joinFactService.findByGroupJids(tenantId, normalized)) {
            result.computeIfAbsent(normalizeGroupJid(row.groupJid()), ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }

    private static String role(WhatsappGroupMemberStateVO participant) {
        if (Boolean.TRUE.equals(participant.owner())) {
            return "群主";
        }
        if (Boolean.TRUE.equals(participant.admin())) {
            return "管理员";
        }
        return "群成员";
    }

    private static String exitType(WhatsappGroupDepartedMemberVO departure) {
        if ("LEFT".equalsIgnoreCase(departure.exitType())) {
            return "主动退群";
        }
        if ("REMOVED".equalsIgnoreCase(departure.exitType())) {
            return "被移出群组";
        }
        return "退出原因未识别";
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

    private static String standardInviteUrl(String value) {
        String normalized = normalize(value);
        if (normalized == null
                || !normalized.toLowerCase(Locale.ROOT).startsWith(INVITE_URL_PREFIX)) {
            return null;
        }
        String code = normalized.substring(INVITE_URL_PREFIX.length());
        int end = code.length();
        for (char separator : new char[]{'?', '#', '/'}) {
            int index = code.indexOf(separator);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        return inviteUrlFromCode(code.substring(0, end));
    }

    private static String inviteUrlFromCode(String value) {
        String code = normalize(value);
        if (code == null || !code.matches("^[A-Za-z0-9_-]+$")) {
            return null;
        }
        return INVITE_URL_PREFIX + code;
    }

    private static String normalizeGroupJid(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return normalized;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.contains("@") ? lower : lower + "@g.us";
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

    private static String maskedGroup(String groupJid) {
        String normalized = normalize(groupJid);
        if (normalized == null || normalized.length() <= 8) {
            return "***";
        }
        return normalized.substring(0, 4) + "***" + normalized.substring(normalized.length() - 4);
    }

    private record GroupKey(Long taskId, String groupJid) {
    }

    private record QueryContext(
            Map<GroupKey, List<Long>> observerIds,
            Map<Long, ProtocolAccountRef> accounts,
            Map<Long, Object> accountLocks,
            Runnable ownershipCheck) {
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

    private record MemberState(
            String role,
            boolean inGroup,
            String exitType,
            Long joinedAt,
            Long exitedAt) {
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
                countryConsumer.accept(countryRow(snapshot.group(), effectivePhone, country, state.joinedAt()));
            }
        }
    }

    private record GroupSnapshot(
            MarketingTaskGroupExportRow group,
            WhatsappGroupMemberCacheSnapshotVO cached) {
    }
}
