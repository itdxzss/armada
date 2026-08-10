package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupInviteLinkChangedEvent;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 当前群邀请链接事实服务实现。 */
@Service
public class GroupInviteLinkServiceImpl implements GroupInviteLinkService {

    private static final Logger log = LoggerFactory.getLogger(GroupInviteLinkServiceImpl.class);

    private final GroupLinkRegistryService registryService;
    private final GroupLinkPreviewMapper previewMapper;

    /** 创建当前群邀请链接事实服务。 */
    public GroupInviteLinkServiceImpl(
            GroupLinkRegistryService registryService,
            GroupLinkPreviewMapper previewMapper) {
        this.registryService = registryService;
        this.previewMapper = previewMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void apply(GroupInviteLinkChangedEvent event) {
        validate(event);
        long observedAt = event.occurredAt();
        Long groupLinkId = registryService.registerAccountObservedGroup(
                event.groupJid().trim(), null, event.protocolBackend(), observedAt);
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid(event.groupJid().trim());
        row.setInviteCode(event.inviteCode().trim());
        row.setInviteCodeObservedAt(observedAt);
        row.setCreatedAt(observedAt);
        row.setUpdatedAt(observedAt);
        previewMapper.upsertInviteLinkChange(row);
        String inviteCode = event.inviteCode().trim();
        log.info("群邀请链接当前事实已接收 eventId={} groupLinkId={} backend={} inviteCodeSuffix={}",
                event.eventId(), groupLinkId, event.protocolBackend(),
                inviteCode.substring(Math.max(0, inviteCode.length() - 6)));
    }

    /** {@inheritDoc} */
    @Override
    public String resolveCurrentInviteCode(Long groupLinkId, String frozenInviteCode) {
        if (groupLinkId != null) {
            GroupLinkPreview preview = previewMapper.selectByGroupLinkId(groupLinkId);
            if (preview != null && hasText(preview.getInviteCode())) {
                return preview.getInviteCode().trim();
            }
        }
        if (!hasText(frozenInviteCode)) {
            throw new BusinessException(ErrorCode.VALIDATION, "群邀请码为空");
        }
        return frozenInviteCode.trim();
    }

    private static void validate(GroupInviteLinkChangedEvent event) {
        if (event == null || !hasText(event.eventId()) || !hasText(event.groupJid())
                || !hasText(event.inviteCode()) || event.protocolBackend() == null
                || event.occurredAt() == null || event.occurredAt() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群邀请链接变更事实不完整");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
