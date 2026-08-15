package com.armada.group.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.GroupId;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.SyncStateWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Write;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将当前 v1 账号群快照集合化写入 V117 的 S/G/P/M/B 五表。 */
@Service
public class AccountGroupCurrentSnapshotPersistenceImpl {

    private static final Logger log = LoggerFactory.getLogger(
            AccountGroupCurrentSnapshotPersistenceImpl.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final int DISPLAY_NAME_MAX_LENGTH = 128;
    private static final int SUBJECT_MAX_LENGTH = 255;
    private static final int AVATAR_URL_MAX_LENGTH = 512;
    private static final int EVENT_ID_MAX_LENGTH = 255;
    private static final int PRESENCE_IN_GROUP = 1;
    private static final String SNAPSHOT_SOURCE = "GROUP_SNAPSHOT";
    private final AccountGroupCurrentSnapshotMapper mapper;
    private final ObjectMapper objectMapper;

    public AccountGroupCurrentSnapshotPersistenceImpl(
            AccountGroupCurrentSnapshotMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 替换单账号当前可见群。当前阶段未接入事件入口，只提供可真实验证的五表批量写能力。
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceVisibleGroups(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            boolean snapshotComplete,
            long syncAt,
            String eventId) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模型快照缺少 accountId");
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        Context context = mapper.selectContext(accountId);
        if (context == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模型快照找不到活跃账号");
        }
        WhatsappJids.OwnerIdentity self = WhatsappJids.ownerIdentity(context.wsPhone(), "pn");
        if (self.kind() != OwnerIdentityKind.PN
                || self.ownerJid() == null
                || self.ownerPhone() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号手机号无法构造 self PN JID");
        }

        long now = System.currentTimeMillis();
        Map<String, AccountGroupsReportedEvent.Group> visible = normalizedGroups(groups);
        List<String> visibleJids = List.copyOf(visible.keySet());
        BaselineEvidence baseline = baselineEvidence(context);
        List<Existing> existingRows = mapper.selectExisting(
                accountId, self.ownerJid(), visibleJids);
        Map<String, Existing> existingByJid = existingRows.stream()
                .filter(row -> visible.containsKey(row.groupJid()))
                .collect(Collectors.toMap(
                        Existing::groupJid,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        String normalizedEventId = clamp(blankToNull(eventId), EVENT_ID_MAX_LENGTH);
        List<Write> rows = new ArrayList<>(visible.size());
        for (Map.Entry<String, AccountGroupsReportedEvent.Group> entry : visible.entrySet()) {
            Existing existing = existingByJid.get(entry.getKey());
            boolean acceptedPresence = snapshotWins(existing, syncAt);
            Classification classification = classification(
                    entry.getKey(), baseline, acceptedPresence, syncAt);
            AccountGroupsReportedEvent.Group group = entry.getValue();
            String subject = clamp(blankToNull(group.subject()), SUBJECT_MAX_LENGTH);
            String displayName = subject == null
                    ? existing == null ? entry.getKey() : null
                    : clamp(subject, DISPLAY_NAME_MAX_LENGTH);
            Long activeSince = acceptedPresence
                    && (existing == null || existing.presenceStatus() == null
                    || existing.presenceStatus() != PRESENCE_IN_GROUP)
                    ? syncAt : null;
            rows.add(new Write(
                    null,
                    entry.getKey(),
                    displayName,
                    clamp(blankToNull(group.avatarUrl()), AVATAR_URL_MAX_LENGTH),
                    subject,
                    group.memberCount(),
                    toEpochMillis(group.groupCreatedAt()),
                    group.announceOnly(),
                    self.ownerJid(),
                    self.ownerPhone(),
                    role(group.admin()),
                    normalizedEventId,
                    syncAt,
                    now,
                    classification.wasInInitialBaseline(),
                    classification.baselineSubjectSnapshot(),
                    activeSince,
                    classification.firstPostControlObservedAt()));
        }

        if (!rows.isEmpty()) {
            List<Write> missingGroupRows = rows.stream()
                    .filter(row -> !existingByJid.containsKey(row.groupJid()))
                    .toList();
            if (!missingGroupRows.isEmpty()) {
                mapper.insertMissingGroups(tenantId, missingGroupRows);
            }
            Map<String, Long> groupIds = mapper.selectGroupIds(tenantId, visibleJids).stream()
                    .collect(Collectors.toMap(GroupId::groupJid, GroupId::groupId));
            if (groupIds.size() != rows.size()) {
                throw new BusinessException(ErrorCode.CONFLICT, "新群模型批量解析 groupId 不完整");
            }
            rows = rows.stream()
                    .map(row -> row.withGroupId(groupIds.get(row.groupJid())))
                    .sorted(Comparator.comparing(Write::groupId).thenComparing(Write::groupJid))
                    .toList();
            mapper.upsertProfiles(rows);
            mapper.upsertParticipants(rows);
        }

        if (snapshotComplete) {
            List<Long> missingParticipantIds = existingRows.stream()
                    .filter(row -> row.bindingId() != null)
                    .filter(row -> row.participantId() != null)
                    .filter(row -> !visible.containsKey(row.groupJid()))
                    .filter(row -> snapshotWins(row, syncAt))
                    .map(Existing::participantId)
                    .distinct()
                    .sorted()
                    .toList();
            if (!missingParticipantIds.isEmpty()) {
                mapper.markMissingParticipants(
                        missingParticipantIds, syncAt, normalizedEventId, now);
            }
        }

        if (!rows.isEmpty()) {
            mapper.upsertBindings(tenantId, accountId, rows);
        }

        mapper.upsertSyncState(syncState(context, baseline, snapshotComplete, syncAt, now));
    }

    private SyncStateWrite syncState(
            Context context,
            BaselineEvidence baseline,
            boolean snapshotComplete,
            long syncAt,
            long now) {
        return new SyncStateWrite(
                context.accountId(),
                baseline.state(),
                baseline.completeness(),
                baseline.capturedAt(),
                baseline.groupCount(),
                context.lastSyncRequestedAt(),
                syncAt,
                snapshotComplete,
                snapshotComplete ? syncAt : null,
                now);
    }

    private BaselineEvidence baselineEvidence(Context context) {
        int state = switch (context.baselineState() == null ? AccountGroupBaselineStateCode.PENDING
                : context.baselineState()) {
            case AccountGroupBaselineStateCode.CAPTURED -> AccountGroupBaselineStateCode.CAPTURED;
            case AccountGroupBaselineStateCode.DISABLED -> AccountGroupBaselineStateCode.DISABLED;
            default -> AccountGroupBaselineStateCode.PENDING;
        };
        if (state != AccountGroupBaselineStateCode.CAPTURED) {
            return new BaselineEvidence(state, 0, null, null, Set.of(), Map.of(), false);
        }
        if (context.baselineCapturedAt() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "已拍 baseline 缺少 capturedAt");
        }
        ParsedBaseline parsed = parseBaseline(context);
        boolean previouslyProven = context.targetBaselineCompleteness() != null
                && context.targetBaselineCompleteness() == 1;
        int completeness = previouslyProven || (parsed.valid() && !parsed.groupJids().isEmpty())
                ? 1 : 2;
        Integer groupCount = completeness == 1
                ? parsed.valid() ? parsed.groupJids().size() : context.baselineGroupCount()
                : context.baselineGroupCount();
        if (completeness == 1 && groupCount == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "完整 baseline 缺少群数量");
        }
        return new BaselineEvidence(
                state,
                completeness,
                context.baselineCapturedAt(),
                groupCount,
                parsed.groupJids(),
                parsed.subjects(),
                parsed.valid() && completeness == 1);
    }

    private ParsedBaseline parseBaseline(Context context) {
        if (context.baselineGroupJidsJson() == null) {
            return new ParsedBaseline(Set.of(), Map.of(), false);
        }
        try {
            List<String> rawJids = objectMapper.readValue(
                    context.baselineGroupJidsJson(), STRING_LIST);
            Set<String> groupJids = new LinkedHashSet<>();
            for (String rawJid : rawJids) {
                String groupJid = normalizeJid(rawJid);
                if (groupJid != null) {
                    groupJids.add(groupJid);
                }
            }
            Map<String, String> subjects = new LinkedHashMap<>();
            if (context.baselineGroupSubjectsJson() != null) {
                Map<String, String> rawSubjects = objectMapper.readValue(
                        context.baselineGroupSubjectsJson(), STRING_MAP);
                rawSubjects.forEach((rawJid, rawSubject) -> {
                    String groupJid = normalizeJid(rawJid);
                    String subject = clamp(blankToNull(rawSubject), SUBJECT_MAX_LENGTH);
                    if (groupJid != null && subject != null) {
                        subjects.putIfAbsent(groupJid, subject);
                    }
                });
            }
            return new ParsedBaseline(Set.copyOf(groupJids), Map.copyOf(subjects), true);
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn("账号 baseline JSON 无法安全映射到新群模型 accountId={}", context.accountId());
            return new ParsedBaseline(Set.of(), Map.of(), false);
        }
    }

    private static Classification classification(
            String groupJid,
            BaselineEvidence baseline,
            boolean acceptedPresence,
            long syncAt) {
        if (!acceptedPresence || !baseline.classifiable()) {
            return Classification.unknown();
        }
        if (baseline.groupJids().contains(groupJid)) {
            return new Classification(1, baseline.subjects().get(groupJid), null);
        }
        if (baseline.capturedAt() != null && syncAt > baseline.capturedAt()) {
            return new Classification(0, null, syncAt);
        }
        return Classification.unknown();
    }

    private static boolean snapshotWins(Existing existing, long syncAt) {
        if (existing == null || existing.presenceObservedAt() == null) {
            return true;
        }
        if (syncAt != existing.presenceObservedAt()) {
            return syncAt > existing.presenceObservedAt();
        }
        return sourcePriority(SNAPSHOT_SOURCE) > sourcePriority(existing.presenceSource());
    }

    private static int sourcePriority(String source) {
        if (source == null) {
            return 0;
        }
        return switch (source) {
            case "WGP2_REMOVE", "WGP2_LEAVE" -> 5;
            case "WGP2_PROMOTE", "WGP2_DEMOTE" -> 4;
            case "WGP2_ADD" -> 3;
            case "GROUP_MEMBER_QUERY" -> 2;
            case SNAPSHOT_SOURCE -> 1;
            default -> 0;
        };
    }

    private static Map<String, AccountGroupsReportedEvent.Group> normalizedGroups(
            List<AccountGroupsReportedEvent.Group> groups) {
        Map<String, AccountGroupsReportedEvent.Group> normalized = new TreeMap<>();
        if (groups == null) {
            return normalized;
        }
        for (AccountGroupsReportedEvent.Group group : groups) {
            if (group == null) {
                continue;
            }
            String groupJid = normalizeJid(group.groupJid());
            if (groupJid != null) {
                normalized.putIfAbsent(groupJid, group);
            }
        }
        return normalized;
    }

    private static int role(Boolean admin) {
        if (admin == null) {
            return 0;
        }
        return admin ? 2 : 1;
    }

    private static Long toEpochMillis(Long epochSeconds) {
        return epochSeconds == null ? null : Math.multiplyExact(epochSeconds, 1_000L);
    }

    private static String normalizeJid(String value) {
        return blankToNull(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clamp(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? value : value.substring(0, maxLength);
    }

    private record ParsedBaseline(
            Set<String> groupJids,
            Map<String, String> subjects,
            boolean valid) {
    }

    private record BaselineEvidence(
            int state,
            int completeness,
            Long capturedAt,
            Integer groupCount,
            Set<String> groupJids,
            Map<String, String> subjects,
            boolean classifiable) {
    }

    private record Classification(
            Integer wasInInitialBaseline,
            String baselineSubjectSnapshot,
            Long firstPostControlObservedAt) {

        private static Classification unknown() {
            return new Classification(null, null, null);
        }
    }
}
