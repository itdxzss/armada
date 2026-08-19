package com.armada.group.service.impl;

import com.armada.group.model.dto.GroupMetadataPatch;
import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.model.dto.GroupMetadataPatchField;
import com.armada.group.model.enums.GroupMetadataFieldSource;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupMetadataPatchService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupProfileReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupProfileReportedSink;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.util.WhatsappJids;
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
    private final GroupMetadataSyncTaskMapper taskMapper;
    private final GroupBatchTaskItemMapper batchItemMapper;
    private final GroupLinkRegistryService groupLinkRegistryService;
    private final GroupCreatorCompatibilityWriter creatorWriter;

    public GroupProfileReportedSinkAdapter(
            GroupMetadataPatchService patchService,
            AccountGroupCurrentSnapshotPersistenceImpl snapshotPersistence,
            GroupMetadataSyncTaskMapper taskMapper,
            GroupBatchTaskItemMapper batchItemMapper,
            GroupLinkRegistryService groupLinkRegistryService,
            GroupCreatorCompatibilityWriter creatorWriter) {
        this.patchService = patchService;
        this.snapshotPersistence = snapshotPersistence;
        this.taskMapper = taskMapper;
        this.batchItemMapper = batchItemMapper;
        this.groupLinkRegistryService = groupLinkRegistryService;
        this.creatorWriter = creatorWriter;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleProfileReported(ProtocolGroupProfileReportedEvent event) {
        TenantContext.set(event.tenantId());
        try {
            Long groupLinkId = registerGroupLink(event);
            writeCreator(event, groupLinkId);
            // 建群时间先于资料字段写：后者可能因 fieldMask 为空而整个跳过。
            snapshotPersistence.fillGroupCreatedAt(event.groupJid(), event.groupCreatedAt());
            applyProfileFields(event);
            applyMembers(event);
            if (event.commandId() != null && !event.commandId().isBlank()) {
                taskMapper.markScopeCompleted(event.commandId(), 1, event.occurredAt());
                batchItemMapper.markScopeCompleted(event.commandId(), 1, event.occurredAt());
            }
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 把群登记进群组列表主表 group_link。
     *
     * <p>群组列表读的是 group_link，而不是资料表。此前只有精确关系事件登记它，于是资料几秒就到位、
     * 列表却要等那条事件——它走账号群同步 topic，会排在上线全量清单后面，实测堵了 5 分 49 秒。
     * 本事件自带群 JID 与群名，登记所需信息齐全，且 registerAccountObservedGroup 幂等，
     * 与关系事件重复登记只会 touch 同一行。</p>
     *
     * <p>登记失败不向上抛：资料与成员才是本事件的主载荷，不能因为列表入口没建成而整条消息重投，
     * 那会把这个群的资料也一起卡住。关系事件随后仍会补登记。</p>
     */
    private Long registerGroupLink(ProtocolGroupProfileReportedEvent event) {
        try {
            return groupLinkRegistryService.registerAccountObservedGroup(
                    event.groupJid(),
                    event.subject(),
                    // 用容错解析而非 valueOf：backend 来自外部事件，未知值该回落而不是抛异常。
                    ProtocolBackend.fromProtocolId(event.protocolBackend()),
                    System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("协议群资料上报登记群入口失败,资料与成员照常落库 eventId={} groupJid={} reason={}",
                    event.eventId(), event.groupJid(), e.getMessage());
            return null;
        }
    }

    /**
     * 写创建者与国旗；两者同源于建群人手机号。
     *
     * <p>与登记群入口同样只告警不抛出：创建者是展示字段，不该让整条资料事件因它重投。</p>
     */
    private void writeCreator(ProtocolGroupProfileReportedEvent event, Long groupLinkId) {
        if (groupLinkId == null || event.creatorPhone() == null) {
            return;
        }
        try {
            creatorWriter.writeCreator(groupLinkId, event.creatorPhone(), event.occurredAt());
        } catch (RuntimeException e) {
            log.warn("协议群资料上报写创建者失败,其余事实照常落库 eventId={} groupJid={} reason={}",
                    event.eventId(), event.groupJid(), e.getMessage());
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
                    // 协议侧可能送裸号码，也可能已经是完整 JID，一律交给 WhatsappJids 归一：
                    // 自己拼后缀会在后者上拼出双后缀，而绑定按 pn_jid 等值关联，受控账号从此匹配不上。
                    pnJid(member.phone()),
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

    /**
     * 把协议侧号码归一成 PN JID；缺号码或号码非法时返回 null，成员事实仍照常落库。
     *
     * <p>协议侧可能送裸号码，也可能已经送完整 JID，{@link WhatsappJids#userJid} 对后者原样返回，
     * 所以这里不能自己拼后缀。单个成员号码非法不应炸掉整批快照——落库层允许 pn_jid 为空，
     * 代价只是这一个成员关联不到受控账号。</p>
     *
     * @param phone 协议侧还原的号码，可能是裸号码、完整 JID 或 null
     * @return 归一后的 PN JID；不可用时为 null
     */
    private static String pnJid(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        try {
            return WhatsappJids.userJid(phone);
        } catch (ProtocolException e) {
            log.warn("协议成员号码无法归一成 PN JID,该成员按无号码落库 phone={}", phone);
            return null;
        }
    }
}
