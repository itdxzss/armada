package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.model.vo.GroupCurrentIdentity;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupInvitePageMetadata;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 当前群邀请链接事实服务实现。 */
@Service
public class GroupInviteLinkServiceImpl implements GroupInviteLinkService {

    private static final Logger log = LoggerFactory.getLogger(GroupInviteLinkServiceImpl.class);

    private final GroupLinkRegistryService registryService;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupExecutionAccountSelector accountSelector;
    private final GroupInvitePort invitePort;
    private final GroupCurrentInvitePersistence currentInvitePersistence;

    /**
     * 创建当前群邀请链接事实服务。
     *
     * @param registryService 群入口登记服务
     * @param accountSelector 群内执行账号选择器
     * @param invitePort 群邀请链接协议端口
     * @param currentInvitePersistence 新群模型当前邀请码写入
     */
    public GroupInviteLinkServiceImpl(
            GroupLinkRegistryService registryService,
            GroupLinkMapper groupLinkMapper,
            GroupExecutionAccountSelector accountSelector,
            GroupInvitePort invitePort,
            GroupCurrentInvitePersistence currentInvitePersistence) {
        this.registryService = registryService;
        this.groupLinkMapper = groupLinkMapper;
        this.accountSelector = accountSelector;
        this.invitePort = invitePort;
        this.currentInvitePersistence = currentInvitePersistence;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyCurrentInvite(GroupInviteLinkObservation observation) {
        validate(observation);
        long observedAt = observation.observedAt();
        Long groupLinkId = observation.groupLinkId() == null
                ? registryService.registerAccountObservedGroup(
                        observation.groupJid().trim(), null,
                        observation.protocolBackend(), observedAt)
                : observation.groupLinkId();
        GroupLinkHealth health = restoreAvailableHealth(groupLinkId, observedAt);
        String inviteCode = observation.inviteCode().trim();
        currentInvitePersistence.apply(groupLinkId, observation.groupJid(), inviteCode, observedAt);
        String resolvedGroupJid = trimToNull(observation.groupJid());
        if (resolvedGroupJid == null) {
            GroupCurrentIdentity current = groupLinkMapper.selectCurrentIdentity(groupLinkId);
            resolvedGroupJid = current == null ? null : trimToNull(current.groupJid());
        }
        if (resolvedGroupJid != null && health != null) {
            currentInvitePersistence.applyHealth(resolvedGroupJid, health);
        }
        log.info("群邀请链接当前事实已接收 observationId={} groupLinkId={} source={} "
                        + "backend={} inviteCodeSuffix={}",
                observation.observationId(), groupLinkId, observation.source(),
                observation.protocolBackend(),
                inviteCode.substring(Math.max(0, inviteCode.length() - 6)));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindGroupJid(Long groupLinkId, String groupJid, long observedAt) {
        if (groupLinkId == null || !hasText(groupJid) || observedAt <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群入口与群 JID 绑定事实不完整");
        }
        GroupCurrentIdentity current = groupLinkMapper.selectCurrentIdentity(groupLinkId);
        currentInvitePersistence.bindGroup(groupLinkId, groupJid, observedAt);
        if (current != null && hasText(current.inviteCode())) {
            currentInvitePersistence.apply(
                    groupLinkId, groupJid, current.inviteCode().trim(), observedAt);
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyPublicPreview(
            Long groupLinkId, Long labelId, GroupInvitePageMetadata metadata, long observedAt) {
        if (groupLinkId == null || labelId == null || metadata == null
                || !hasText(metadata.inviteCode())
                || observedAt <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "公开邀请页资料不完整");
        }
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupLinkId(groupLinkId);
        preview.setInviteCode(metadata.inviteCode());
        preview.setWaSubject(metadata.waSubject());
        preview.setAvatarUrl(metadata.avatarUrl());
        preview.setLastPreviewAt(observedAt);
        preview.setCreatedAt(observedAt);
        preview.setUpdatedAt(observedAt);
        currentInvitePersistence.applyPublicPreview(preview, labelId);
    }

    /** {@inheritDoc} */
    @Override
    public String resolveCurrentInviteCode(Long groupLinkId, String frozenInviteCode) {
        if (groupLinkId != null) {
            GroupCurrentIdentity identity = groupLinkMapper.selectCurrentIdentity(groupLinkId);
            if (identity != null && hasText(identity.inviteCode())) {
                return identity.inviteCode().trim();
            }
        }
        if (!hasText(frozenInviteCode)) {
            throw new BusinessException(ErrorCode.VALIDATION, "群邀请码为空");
        }
        return frozenInviteCode.trim();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> refreshCurrentInviteCode(
            Long groupLinkId, String groupJid, String attemptedInviteCode) {
        if (groupLinkId == null) {
            return Optional.empty();
        }
        GroupCurrentIdentity identity = groupLinkMapper.selectCurrentIdentity(groupLinkId);
        if (identity == null || !hasText(identity.inviteCode())) {
            return queryCurrentInvite(groupLinkId, groupJid, attemptedInviteCode, identity);
        }
        String currentInviteCode = identity.inviteCode().trim();
        if (!currentInviteCode.equals(trimToEmpty(attemptedInviteCode))) {
            return Optional.of(currentInviteCode);
        }
        return queryCurrentInvite(groupLinkId, groupJid, attemptedInviteCode, identity);
    }

    private Optional<String> queryCurrentInvite(
            Long groupLinkId,
            String groupJid,
            String attemptedInviteCode,
            GroupCurrentIdentity identity) {
        String resolvedGroupJid = hasText(groupJid)
                ? groupJid.trim()
                : identity == null ? null : trimToNull(identity.groupJid());
        if (resolvedGroupJid == null) {
            return Optional.empty();
        }
        GroupExecutionAccount admin = accountSelector.findCandidates(groupLinkId).stream()
                .filter(GroupExecutionAccount::groupAdmin)
                .findFirst()
                .orElse(null);
        if (admin == null) {
            return Optional.empty();
        }
        GroupInviteResult invite = invitePort.getInvite(admin.protocolRef(), resolvedGroupJid);
        String inviteCode = inviteCode(invite);
        if (inviteCode == null) {
            return Optional.empty();
        }
        long observedAt = System.currentTimeMillis();
        applyCurrentInvite(new GroupInviteLinkObservation(
                "active-query:" + groupLinkId + ":" + observedAt,
                groupLinkId, resolvedGroupJid, inviteCode,
                admin.protocolRef().backend(), "ACTIVE_QUERY", observedAt));
        return inviteCode.equals(trimToEmpty(attemptedInviteCode))
                ? Optional.empty()
                : Optional.of(inviteCode);
    }

    private static void validate(GroupInviteLinkObservation observation) {
        if (observation == null || !hasText(observation.observationId())
                || !hasText(observation.inviteCode())
                || observation.observedAt() == null || observation.observedAt() <= 0
                || observation.groupLinkId() == null && !hasText(observation.groupJid())
                || observation.groupLinkId() == null && observation.protocolBackend() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群邀请链接变更事实不完整");
        }
    }

    private GroupLinkHealth restoreAvailableHealth(Long groupLinkId, long observedAt) {
        GroupLinkHealth row = new GroupLinkHealth();
        row.setGroupLinkId(groupLinkId);
        row.setHealthStatus(GroupLinkHealthStatus.AVAILABLE.code());
        row.setBanned(false);
        row.setLastCheckAt(observedAt);
        row.setLastHealthError(null);
        row.setHealthFailureCount(0);
        row.setCreatedAt(observedAt);
        row.setUpdatedAt(observedAt);
        return row;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String inviteCode(GroupInviteResult invite) {
        if (invite == null) {
            return null;
        }
        String code = trimToNull(invite.inviteCode());
        if (code != null) {
            return code;
        }
        String url = trimToNull(invite.inviteUrl());
        if (url == null) {
            return null;
        }
        int slash = url.lastIndexOf('/');
        return slash < 0 || slash == url.length() - 1 ? null : url.substring(slash + 1);
    }
}
