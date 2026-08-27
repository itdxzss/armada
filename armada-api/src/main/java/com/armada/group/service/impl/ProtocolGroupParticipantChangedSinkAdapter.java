package com.armada.group.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.model.dto.ControlledAccountGroupTransition;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.dto.WhatsappGroupIdentityMergeFact;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.service.GroupParticipantObservationService;
import com.armada.group.service.WhatsappGroupMemberCacheService;
import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import com.armada.marketing.service.MarketingNewGroupImmediateSendService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupDepartureEvent;
import com.armada.platform.kafka.consumer.account.ProtocolGroupDepartureSink;
import com.armada.platform.kafka.consumer.account.ProtocolGroupJoinEvent;
import com.armada.platform.kafka.consumer.account.ProtocolGroupJoinSink;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantChangedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantChangedSink;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantIdentity;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把 platform 群成员事件转换为 group 域观察事实。
 *
 * <p>promote/demote 只改角色，走成员观察路径；add/remove 改的是在群与否，复用 Android 已有的
 * 进群/退群事实路径，两端因此落到同一批列（presence 与 last_joined_at/last_exited_at/exit_type），
 * 并且不会把未观察到的角色写成"普通成员"。</p>
 *
 * <p>进群/退群事实路径不负责受控账号的群关系，所以写完事实必须再收敛一次——受控号自己进退群时
 * 关系不跟着变，选号会继续按旧关系派活。</p>
 */
@Service
public class ProtocolGroupParticipantChangedSinkAdapter
        implements ProtocolGroupParticipantChangedSink {

    private static final Logger log = LoggerFactory.getLogger(
            ProtocolGroupParticipantChangedSinkAdapter.class);

    /** 成员进群；WhatsApp 不能直接加成管理员，所以本动作不提供角色事实。 */
    private static final String ACTION_ADD = "add";

    /** 成员离开群，含主动退群与被移出两种情况。 */
    private static final String ACTION_REMOVE = "remove";

    /** 成员被提升为管理员。 */
    private static final String ACTION_PROMOTE = "promote";

    /** 成员被取消管理员。 */
    private static final String ACTION_DEMOTE = "demote";

    /** 同一个人的身份形态变化，只合并 PN/LID，不改在群态与角色。 */
    private static final String ACTION_MODIFY = "modify";

    /** 成员被移出群，操作人与目标明确不是同一人。 */
    private static final String EXIT_TYPE_REMOVED = "REMOVED";

    /** 成员主动退群，操作人就是目标本人。 */
    private static final String EXIT_TYPE_LEFT = "LEFT";

    /** 无法可靠区分主动退群与被移除；批量 remove 与身份不可同形比较时只能给这个。 */
    private static final String EXIT_TYPE_UNKNOWN = "UNKNOWN";

    /** Web 实时成员通知来源，与 Android 的 WGP2_NOTIFICATION 区分开。 */
    private static final String SOURCE_TYPE_WEB = "WEB_NOTIFICATION";

    /** Android WGP2 实时成员通知来源。 */
    private static final String SOURCE_TYPE_ANDROID = "WGP2_NOTIFICATION";

    private final AccountMapper accountMapper;
    private final GroupParticipantObservationService observationService;
    private final ProtocolGroupJoinSink joinSink;
    private final ProtocolGroupDepartureSink departureSink;
    private final WhatsappGroupMemberCacheService memberCacheService;
    private final MarketingNewGroupImmediateSendService marketingNewGroupService;

    public ProtocolGroupParticipantChangedSinkAdapter(
            AccountMapper accountMapper,
            GroupParticipantObservationService observationService,
            ProtocolGroupJoinSink joinSink,
            ProtocolGroupDepartureSink departureSink,
            WhatsappGroupMemberCacheService memberCacheService,
            MarketingNewGroupImmediateSendService marketingNewGroupService) {
        this.accountMapper = accountMapper;
        this.observationService = observationService;
        this.joinSink = joinSink;
        this.departureSink = departureSink;
        this.memberCacheService = memberCacheService;
        this.marketingNewGroupService = marketingNewGroupService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleParticipantChanged(ProtocolGroupParticipantChangedEvent event) {
        long receivedAt = System.currentTimeMillis();
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            Account current = accountMapper.selectActiveById(event.accountId());
            if (!currentBinding(current, event)) {
                log.warn(
                        "忽略账号不可见或协议绑定已过期的群成员事件 tenantId={} accountId={} eventId={}",
                        event.tenantId(), event.accountId(), event.eventId());
                return;
            }
            Long ownerUserId = current.getOwnerUserId();
            if (ownerUserId == null) {
                throw new BusinessException(
                        ErrorCode.ACCESS_DENIED,
                        "历史无归属账号不能消费用户私有群成员事件");
            }
            try (DataScopeContext.Scope ignored =
                         DataScopeContext.open(DataScope.self(ownerUserId))) {
                switch (event.action()) {
                    case ACTION_ADD -> applyJoins(event, receivedAt);
                    case ACTION_REMOVE -> applyDepartures(event);
                    case ACTION_PROMOTE, ACTION_DEMOTE -> applyRoleObservations(event);
                    case ACTION_MODIFY -> applyIdentityMerges(event);
                    default -> log.debug("协议群成员事件动作尚未接入,跳过 eventId={} action={}",
                            event.eventId(), event.action());
                }
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /** promote/demote 只提供角色事实，在群与否沿用观察路径的"角色变更必然在群"口径。 */
    private void applyRoleObservations(ProtocolGroupParticipantChangedEvent event) {
        boolean admin = ACTION_PROMOTE.equals(event.action());
        List<GroupParticipantObservation> observations = event.participants().stream()
                .map(participant -> observation(event, participant, admin))
                .toList();
        observationService.apply(observations);
    }

    /**
     * 把同一个人的两种身份补进同一行成员记录。
     *
     * <p>只有两种身份都拿到才有合并对象：单边身份落库只会凭空多出一行未知态成员，比不写更糟。
     * 号码还原不到 PN 的成员本次跳过，等下一次带号码的观察再合并。</p>
     *
     * <p>本动作不碰在群态与角色——协议只说了身份变了，没说这个人在不在群、是不是管理员。</p>
     */
    private void applyIdentityMerges(ProtocolGroupParticipantChangedEvent event) {
        List<WhatsappGroupIdentityMergeFact> facts = new ArrayList<>(event.participants().size());
        for (ProtocolGroupParticipantIdentity participant : event.participants()) {
            String lidJid = userLevelJid(participant.lid());
            String pnJid = phoneJid(participant);
            if (lidJid == null || pnJid == null) {
                continue;
            }
            facts.add(new WhatsappGroupIdentityMergeFact(
                    event.tenantId(), event.groupJid(), pnJid, lidJid,
                    digits(participant.phoneNumber()), event.occurredAt(),
                    sourceEventId(event, participant)));
        }
        if (facts.isEmpty()) {
            log.info("协议群成员身份变化事件没有可合并的双身份,跳过 eventId={} count={}",
                    event.eventId(), event.participants().size());
            return;
        }
        memberCacheService.applyIdentityMerges(facts);
    }

    /** 先判定受控账号的真实进群跃迁，再复用统一进群事实链路。 */
    private void applyJoins(ProtocolGroupParticipantChangedEvent event, long detectedAt) {
        List<ControlledAccountGroupTransition> transitions = observationService.reconcileControlledJoins(
                event.tenantId(), event.groupJid(), controlledIdentities(event),
                event.occurredAt(), event.eventId());
        List<ProtocolGroupJoinEvent.Participant> participants = event.participants().stream()
                .map(participant -> new ProtocolGroupJoinEvent.Participant(
                        participantJid(participant),
                        participant.phoneNumber(),
                        event.occurredAt(),
                        sourceEventId(event, participant)))
                .toList();
        joinSink.handleJoins(new ProtocolGroupJoinEvent(
                event.eventId(), event.tenantId(), event.accountId(), event.protocolAccountId(),
                event.groupJid(), sourceType(event), event.occurredAt(), participants));
        for (ControlledAccountGroupTransition transition : transitions) {
            marketingNewGroupService.enqueueDelayedNewGroups(
                    transition.accountId(),
                    List.of(new MarketingNewGroupDTO(
                            null, transition.groupJid(), null)),
                    detectedAt);
        }
    }

    /** 退群事实交给统一退群链路，再把受控账号的群关系对齐到落库后的结果。 */
    private void applyDepartures(ProtocolGroupParticipantChangedEvent event) {
        String exitType = exitType(event);
        List<ProtocolGroupDepartureEvent.Participant> participants = event.participants().stream()
                .map(participant -> new ProtocolGroupDepartureEvent.Participant(
                        participantJid(participant),
                        participant.phoneNumber(),
                        exitType,
                        event.occurredAt(),
                        sourceEventId(event, participant)))
                .toList();
        departureSink.handleDepartures(new ProtocolGroupDepartureEvent(
                event.eventId(), event.tenantId(), event.accountId(), event.protocolAccountId(),
                event.groupJid(), sourceType(event), event.occurredAt(), participants));
        reconcileControlledMemberships(event);
    }

    /**
     * 判定退出方式。
     *
     * <p>只有唯一目标、且操作人与目标是同一种身份形态时才敢下结论：PN 与 LID 之间不能互相比较，
     * 猜错的代价是把被踢出群记成主动退群。批量 remove 无法逐个对应操作人，一律按无法判定处理。</p>
     */
    private static String exitType(ProtocolGroupParticipantChangedEvent event) {
        if (event.participants().size() != 1) {
            return EXIT_TYPE_UNKNOWN;
        }
        String operator = userLevelJid(event.operator());
        if (operator == null) {
            return EXIT_TYPE_UNKNOWN;
        }
        ProtocolGroupParticipantIdentity target = event.participants().get(0);
        String comparable = operator.endsWith("@lid")
                ? userLevelJid(target.lid())
                : phoneJid(target);
        if (comparable == null) {
            return EXIT_TYPE_UNKNOWN;
        }
        return operator.equals(comparable) ? EXIT_TYPE_LEFT : EXIT_TYPE_REMOVED;
    }

    /**
     * 收敛受控账号群关系。
     *
     * <p>库里成员行按 PN 优先形态索引，而事件给的身份未必是 PN；同一个人的两种形态都作为候选传下去，
     * 匹配不上的候选在 SQL 里自然落空，不会误伤别人。</p>
     */
    private List<ControlledAccountGroupTransition> reconcileControlledMemberships(
            ProtocolGroupParticipantChangedEvent event) {
        return observationService.reconcileControlledMemberships(
                event.tenantId(), event.groupJid(), controlledIdentities(event));
    }

    private List<String> controlledIdentities(ProtocolGroupParticipantChangedEvent event) {
        List<String> candidates = new ArrayList<>(event.participants().size() * 2);
        for (ProtocolGroupParticipantIdentity participant : event.participants()) {
            addIfPresent(candidates, participantJid(participant));
            addIfPresent(candidates, phoneJid(participant));
            addIfPresent(candidates, userLevelJid(participant.lid()));
        }
        return candidates;
    }

    private static void addIfPresent(List<String> candidates, String value) {
        if (value != null && !candidates.contains(value)) {
            candidates.add(value);
        }
    }

    /** Web 与 Android 的实时成员通知在退出方式可信度上口径不同，来源必须分开标注。 */
    private static String sourceType(ProtocolGroupParticipantChangedEvent event) {
        return ProtocolBackend.ANDROID.name().equals(event.protocolBackend())
                ? SOURCE_TYPE_ANDROID : SOURCE_TYPE_WEB;
    }

    /** 同一条事件内按成员拆分事实 ID，使单个成员的重放天然幂等。 */
    private static String sourceEventId(
            ProtocolGroupParticipantChangedEvent event,
            ProtocolGroupParticipantIdentity participant) {
        return event.eventId() + ":" + participantJid(participant);
    }

    private static String participantJid(ProtocolGroupParticipantIdentity participant) {
        return firstText(participant.lid(), participant.id(), participant.phoneNumber());
    }

    /** 取号码形态的 user-level JID；只有真的拿到号码才给，绝不把 LID 当号码用。 */
    private static String phoneJid(ProtocolGroupParticipantIdentity participant) {
        String phone = digits(participant.phoneNumber());
        if (phone == null) {
            String id = userLevelJid(participant.id());
            return id != null && id.endsWith("@s.whatsapp.net") ? id : null;
        }
        return phone + "@s.whatsapp.net";
    }

    /** 去掉设备号后缀，得到可直接比较的 user-level JID。 */
    private static String userLevelJid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String jid = value.trim().toLowerCase(Locale.ROOT);
        int at = jid.indexOf('@');
        int device = jid.indexOf(':');
        if (device >= 0 && at > device) {
            jid = jid.substring(0, device) + jid.substring(at);
        }
        return jid.endsWith("@lid") || jid.endsWith("@s.whatsapp.net") ? jid : null;
    }

    private static String digits(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.length() >= 5 && digits.length() <= 20 ? digits : null;
    }

    private static boolean currentBinding(
            Account current,
            ProtocolGroupParticipantChangedEvent event) {
        return current != null
                && current.getProtocolAccountId() != null
                && current.getProtocolAccountId().equals(event.protocolAccountId())
                && current.getWsPhone() != null
                && !current.getWsPhone().isBlank()
                && ProtocolBackend.fromProtocolId(current.getProtocolId()).name()
                        .equals(event.protocolBackend());
    }

    private static GroupParticipantObservation observation(
            ProtocolGroupParticipantChangedEvent event,
            ProtocolGroupParticipantIdentity participant,
            boolean admin) {
        String participantJid = participantJid(participant);
        String targetJid = firstText(participant.phoneNumber(), participant.id(), participant.lid());
        return new GroupParticipantObservation(
                event.tenantId(), event.accountId(), event.groupJid(), targetJid,
                participantJid, participant.phoneNumber(), true, admin,
                WhatsappGroupMemberStateSource.ROLE_EVENT,
                event.occurredAt(), event.eventId() + ":" + participantJid);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
