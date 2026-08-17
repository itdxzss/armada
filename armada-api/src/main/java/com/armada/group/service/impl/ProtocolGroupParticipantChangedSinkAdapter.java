package com.armada.group.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.service.GroupParticipantObservationService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupDepartureEvent;
import com.armada.platform.kafka.consumer.account.ProtocolGroupDepartureSink;
import com.armada.platform.kafka.consumer.account.ProtocolGroupJoinEvent;
import com.armada.platform.kafka.consumer.account.ProtocolGroupJoinSink;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantChangedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantChangedSink;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantIdentity;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
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

    private final AccountProtocolLookupService accountLookupService;
    private final GroupParticipantObservationService observationService;
    private final ProtocolGroupJoinSink joinSink;
    private final ProtocolGroupDepartureSink departureSink;

    public ProtocolGroupParticipantChangedSinkAdapter(
            AccountProtocolLookupService accountLookupService,
            GroupParticipantObservationService observationService,
            ProtocolGroupJoinSink joinSink,
            ProtocolGroupDepartureSink departureSink) {
        this.accountLookupService = accountLookupService;
        this.observationService = observationService;
        this.joinSink = joinSink;
        this.departureSink = departureSink;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleParticipantChanged(ProtocolGroupParticipantChangedEvent event) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            ProtocolAccountRef current = accountLookupService.findActiveProtocolRef(event.accountId())
                    .orElse(null);
            if (!currentBinding(current, event)) {
                log.warn(
                        "忽略账号不可见或协议绑定已过期的群成员事件 tenantId={} accountId={} eventId={}",
                        event.tenantId(), event.accountId(), event.eventId());
                return;
            }
            switch (event.action()) {
                case ACTION_ADD -> applyJoins(event);
                case ACTION_REMOVE -> applyDepartures(event);
                case ACTION_PROMOTE, ACTION_DEMOTE -> applyRoleObservations(event);
                default -> log.debug("协议群成员事件动作尚未接入,跳过 eventId={} action={}",
                        event.eventId(), event.action());
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

    /** 进群事实交给统一进群链路，再把受控账号的群关系对齐到落库后的结果。 */
    private void applyJoins(ProtocolGroupParticipantChangedEvent event) {
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
        reconcileControlledMemberships(event);
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
    private void reconcileControlledMemberships(ProtocolGroupParticipantChangedEvent event) {
        List<String> candidates = new ArrayList<>(event.participants().size() * 2);
        for (ProtocolGroupParticipantIdentity participant : event.participants()) {
            addIfPresent(candidates, participantJid(participant));
            addIfPresent(candidates, phoneJid(participant));
            addIfPresent(candidates, userLevelJid(participant.lid()));
        }
        observationService.reconcileControlledMemberships(
                event.tenantId(), event.groupJid(), candidates);
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
            ProtocolAccountRef current,
            ProtocolGroupParticipantChangedEvent event) {
        return current != null
                && current.protocolAccountId().equals(event.protocolAccountId())
                && current.backend().name().equals(event.protocolBackend());
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
