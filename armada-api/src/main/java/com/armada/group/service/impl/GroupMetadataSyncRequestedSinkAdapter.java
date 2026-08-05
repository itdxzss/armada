package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupMetadataSyncRequestedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolGroupMetadataSyncRequestedSink;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** 协议单群详情同步事件到群任务状态机的 adapter。 */
@Service
public class GroupMetadataSyncRequestedSinkAdapter
        implements ProtocolGroupMetadataSyncRequestedSink {

    private final GroupLinkMapper groupLinkMapper;
    private final GroupMetadataSyncTaskService taskService;

    /** 创建事件 adapter。 */
    public GroupMetadataSyncRequestedSinkAdapter(
            GroupLinkMapper groupLinkMapper,
            GroupMetadataSyncTaskService taskService) {
        this.groupLinkMapper = groupLinkMapper;
        this.taskService = taskService;
    }

    @Override
    public void handleGroupMetadataSyncRequested(ProtocolGroupMetadataSyncRequestedEvent event) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            Long groupLinkId = groupLinkMapper.selectActiveIdByGroupJid(event.groupJid());
            if (groupLinkId != null) {
                taskService.enqueue(
                        groupLinkId,
                        GroupMetadataSyncTrigger.valueOf(event.trigger()),
                        event.occurredAt());
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }
}
