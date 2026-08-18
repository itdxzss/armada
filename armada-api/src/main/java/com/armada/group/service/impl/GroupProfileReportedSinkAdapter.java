package com.armada.group.service.impl;

import com.armada.group.model.dto.GroupMetadataPatch;
import com.armada.group.model.dto.GroupMetadataPatchField;
import com.armada.group.model.enums.GroupMetadataFieldSource;
import com.armada.group.service.GroupMetadataPatchService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupProfileReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupProfileReportedSink;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单群完整资料上报事件到 group 域的 adapter。
 *
 * <p>一条事件同时承载资料字段与成员事实，两者写入不同的表但必须在同一事务内完成，否则会出现
 * 资料已更新而成员还是旧的中间态。资料走字段级 reducer（按每字段版本决胜），成员走既有的完整
 * 成员快照落库，两条路径各自幂等。</p>
 *
 * <p>成员部分只在协议明确声明列表完整时执行：既有的完整快照落库会把"库里有而列表里没有"的成员
 * 判为已退群，这个判定必须由协议授权。不完整时只写资料字段并记日志，宁可漏更新也不误判退群——
 * 误判会把在群成员标记为退群，直接影响营销选号与拉群选管理员。</p>
 */
@Service
public class GroupProfileReportedSinkAdapter implements ProtocolGroupProfileReportedSink {

    private static final Logger log =
            LoggerFactory.getLogger(GroupProfileReportedSinkAdapter.class);

    private final GroupMetadataPatchService patchService;
    private final AccountGroupCurrentSnapshotPersistenceImpl snapshotPersistence;

    public GroupProfileReportedSinkAdapter(
            GroupMetadataPatchService patchService,
            AccountGroupCurrentSnapshotPersistenceImpl snapshotPersistence) {
        this.patchService = patchService;
        this.snapshotPersistence = snapshotPersistence;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleProfileReported(ProtocolGroupProfileReportedEvent event) {
        TenantContext.set(event.tenantId());
        try {
            applyProfileFields(event);
            applyMembers(event);
        } finally {
            TenantContext.clear();
        }
    }

    /** 写资料字段；完整快照的可信度低于精确变更事件，同一事实时间下不得压过后者。 */
    private void applyProfileFields(ProtocolGroupProfileReportedEvent event) {
        Set<GroupMetadataPatchField> recognized = EnumSet.noneOf(GroupMetadataPatchField.class);
        int unknown = 0;
        for (String wireName : event.fieldMask()) {
            var field = GroupMetadataPatchField.fromWire(wireName);
            if (field.isPresent()) {
                recognized.add(field.get());
            } else {
                unknown++;
            }
        }
        if (unknown > 0) {
            log.info("协议群资料上报含未识别字段,已跳过 eventId={} groupJid={} unknownCount={}",
                    event.eventId(), event.groupJid(), unknown);
        }
        if (recognized.isEmpty()) {
            return;
        }
        patchService.applyPatch(new GroupMetadataPatch(
                event.tenantId(),
                event.groupJid(),
                recognized,
                event.subject(),
                event.description(),
                event.announceOnly(),
                event.adminOnlyEditInfo(),
                event.memberAddMode(),
                event.joinApprovalMode(),
                event.ephemeralDurationSeconds(),
                GroupMetadataFieldSource.PROFILE_SNAPSHOT,
                event.occurredAt(),
                event.eventId()));
    }

    /** 写完整成员快照；只有协议授权列表完整时才执行，因为它会判定缺失成员已退群。 */
    private void applyMembers(ProtocolGroupProfileReportedEvent event) {
        if (event.members().isEmpty()) {
            return;
        }
        if (!event.membersComplete()) {
            log.info("协议群成员列表未声明完整,跳过成员落库以免误判退群 eventId={} groupJid={} count={}",
                    event.eventId(), event.groupJid(), event.members().size());
            return;
        }
        List<GroupParticipantResult> participants = new ArrayList<>(event.members().size());
        for (ProtocolGroupProfileReportedEvent.Member member : event.members()) {
            participants.add(new GroupParticipantResult(
                    // jid 缺失时用 LID 兜住主标识，落库层按 PN/LID 形态各自归位。
                    member.jid() != null ? member.jid() : member.lid(),
                    // 号码由协议侧还原；控端不猜，缺号码仍保存成员事实，只是关联不到受控账号。
                    member.phone() != null ? member.phone() + "@s.whatsapp.net" : null,
                    member.phone(),
                    member.admin(),
                    member.owner(),
                    member.role()));
        }
        snapshotPersistence.replaceCompleteParticipantSnapshot(
                event.groupJid(),
                participants,
                event.occurredAt(),
                // 事件 ID 作为快照版本，使同一次协议观察的重放天然幂等。
                event.eventId());
    }
}
