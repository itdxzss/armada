package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.vo.GroupPullAccountRefRow;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.port.GroupInvitePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/** 拉群营销邀请链接的即时捕获与末阶段补查。 */
final class GroupPullMarketingInviteLinkSupport {

    private static final Logger log =
            LoggerFactory.getLogger(GroupPullMarketingInviteLinkSupport.class);

    private GroupPullMarketingInviteLinkSupport() {
    }

    static void captureAfterCreate(
            GroupPullMarketingMapper mapper,
            GroupInvitePort invitePort,
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder,
            String groupJid) {
        try {
            String inviteUrl = getInviteUrl(invitePort, builder, groupJid);
            if (!StringUtils.hasText(inviteUrl)) {
                throw new IllegalStateException("协议层未返回群邀请链接");
            }
            if (mapper.saveInitialGroupInviteUrl(
                    execution.getId(), groupJid, inviteUrl, System.currentTimeMillis()) != 1) {
                log.warn(
                        "拉群营销建群后邀请链接未写入 executionId={} groupJid={}",
                        execution.getId(),
                        groupJid);
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "拉群营销建群后立即获取群链接失败 executionId={} reason={}",
                    execution.getId(),
                    compactReason(exception));
        }
    }

    static LookupResult resolveForSave(
            GroupInvitePort invitePort,
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        if (StringUtils.hasText(execution.getGroupInviteUrl())) {
            return new LookupResult(execution.getGroupInviteUrl(), null);
        }
        try {
            return new LookupResult(
                    getInviteUrl(invitePort, builder, execution.getGroupJid()),
                    null);
        } catch (RuntimeException exception) {
            return new LookupResult(null, "群链接获取失败：" + compactReason(exception));
        }
    }

    private static String getInviteUrl(
            GroupInvitePort invitePort,
            GroupPullAccountRefRow builder,
            String groupJid) {
        GroupInviteResult invite = invitePort.getInvite(builder.protocolRef(), groupJid);
        return invite == null ? null : invite.inviteUrl();
    }

    private static String compactReason(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (!StringUtils.hasText(message)) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    record LookupResult(String inviteUrl, String failureReason) {
    }
}
