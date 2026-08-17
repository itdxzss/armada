package com.armada.group.service.impl;

import com.armada.group.model.dto.GroupMetadataPatch;
import com.armada.group.model.dto.GroupMetadataPatchField;
import com.armada.group.model.enums.GroupMetadataFieldSource;
import com.armada.group.service.GroupMetadataPatchService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupMetadataUpdatedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupMetadataUpdatedSink;
import com.armada.shared.tenant.TenantContext;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 群资料字段级变更事件到 group 域 patch 服务的 adapter。
 *
 * <p>业务白名单过滤在此完成：协议 fieldMask 里未识别的字段名计日志后跳过，不阻塞同一事件里
 * 已识别的字段（群变更事件直投影设计 §10）。全部字段都无法识别时确认消费但不写库。</p>
 */
@Service
public class GroupMetadataUpdatedSinkAdapter implements ProtocolGroupMetadataUpdatedSink {

    private static final Logger log =
            LoggerFactory.getLogger(GroupMetadataUpdatedSinkAdapter.class);

    private final GroupMetadataPatchService patchService;

    public GroupMetadataUpdatedSinkAdapter(GroupMetadataPatchService patchService) {
        this.patchService = patchService;
    }

    @Override
    public void handleMetadataUpdated(ProtocolGroupMetadataUpdatedEvent event) {
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
            // 只记字段名与数量，不记字段值：值可能含群名、描述等业务内容。
            log.info("协议群资料事件含未识别字段,已跳过 eventId={} groupJid={} unknownCount={} mask={}",
                    event.eventId(), event.groupJid(), unknown, event.fieldMask());
        }
        if (recognized.isEmpty()) {
            log.info("协议群资料事件无可识别字段,确认消费 eventId={} groupJid={}",
                    event.eventId(), event.groupJid());
            return;
        }

        // 事件已在 consumer 校验 tenantId 存在；租户上下文由本地事务边界持有，供 mapper 拦截器使用。
        TenantContext.set(event.tenantId());
        try {
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
                    // 精确字段事件的可信度高于任何完整快照，同一事实时间下由它胜出。
                    GroupMetadataFieldSource.METADATA_EVENT,
                    event.occurredAt(),
                    event.eventId()));
        } finally {
            TenantContext.clear();
        }
    }
}
