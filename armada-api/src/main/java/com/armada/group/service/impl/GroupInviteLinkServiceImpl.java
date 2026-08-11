package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
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
    private final GroupLinkPreviewMapper previewMapper;
    private final GroupLinkHealthMapper healthMapper;
    private final GroupExecutionAccountSelector accountSelector;
    private final GroupInvitePort invitePort;

    /**
     * 创建当前群邀请链接事实服务。
     *
     * @param registryService 群入口登记服务
     * @param previewMapper 当前群预览数据访问
     * @param healthMapper 群链接健康数据访问
     * @param accountSelector 群内执行账号选择器
     * @param invitePort 群邀请链接协议端口
     */
    public GroupInviteLinkServiceImpl(
            GroupLinkRegistryService registryService,
            GroupLinkPreviewMapper previewMapper,
            GroupLinkHealthMapper healthMapper,
            GroupExecutionAccountSelector accountSelector,
            GroupInvitePort invitePort) {
        this.registryService = registryService;
        this.previewMapper = previewMapper;
        this.healthMapper = healthMapper;
        this.accountSelector = accountSelector;
        this.invitePort = invitePort;
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
        storeCurrentInvite(
                groupLinkId, observation.groupJid(), observation.inviteCode(), observedAt);
        String inviteCode = observation.inviteCode().trim();
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
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid(groupJid.trim());
        row.setCreatedAt(observedAt);
        row.setUpdatedAt(observedAt);
        previewMapper.upsertGroupJidBinding(row);
    }

    private void storeCurrentInvite(
            Long groupLinkId, String groupJid, String inviteCode, long observedAt) {
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid(hasText(groupJid) ? groupJid.trim() : null);
        row.setInviteCode(inviteCode.trim());
        row.setInviteCodeObservedAt(observedAt);
        row.setCreatedAt(observedAt);
        row.setUpdatedAt(observedAt);
        previewMapper.upsertInviteLinkChange(row);
        restoreAvailableHealth(groupLinkId, observedAt);
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

    /** {@inheritDoc} */
    @Override
    public Optional<String> refreshCurrentInviteCode(
            Long groupLinkId, String groupJid, String attemptedInviteCode) {
        if (groupLinkId == null) {
            return Optional.empty();
        }
        GroupLinkPreview preview = previewMapper.selectByGroupLinkId(groupLinkId);
        if (preview == null || !hasText(preview.getInviteCode())) {
            return queryCurrentInvite(groupLinkId, groupJid, attemptedInviteCode, preview);
        }
        String currentInviteCode = preview.getInviteCode().trim();
        if (!currentInviteCode.equals(trimToEmpty(attemptedInviteCode))) {
            return Optional.of(currentInviteCode);
        }
        return queryCurrentInvite(groupLinkId, groupJid, attemptedInviteCode, preview);
    }

    private Optional<String> queryCurrentInvite(
            Long groupLinkId,
            String groupJid,
            String attemptedInviteCode,
            GroupLinkPreview preview) {
        String resolvedGroupJid = hasText(groupJid)
                ? groupJid.trim()
                : preview == null ? null : trimToNull(preview.getGroupJid());
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

    private void restoreAvailableHealth(Long groupLinkId, long observedAt) {
        GroupLinkHealth row = new GroupLinkHealth();
        row.setGroupLinkId(groupLinkId);
        row.setHealthStatus(GroupLinkHealthStatus.AVAILABLE.code());
        row.setBanned(false);
        row.setLastCheckAt(observedAt);
        row.setLastHealthError(null);
        row.setHealthFailureCount(0);
        row.setCreatedAt(observedAt);
        row.setUpdatedAt(observedAt);
        if (healthMapper.updateAvailableFromInviteObservation(row) == 0) {
            healthMapper.insertAvailableFromInviteObservationIfAbsent(row);
            healthMapper.updateAvailableFromInviteObservation(row);
        }
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
