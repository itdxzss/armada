package com.armada.group.service.impl;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupLinkUrls;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 群组池内部登记服务实现。
 *
 * <p>本类是 group 域暴露给其它业务域的窄入口,用于把「已由其它业务发现的群邀请链接」
 * 写入统一的 group_link 群组池。它只负责本地登记/复活 group_link,不调用协议层,
 * 也不做进群、预览、健康检测等外部动作。</p>
 *
 * <p>进群任务使用本服务时,链接来源固定为 JOIN_TASK,关系态固定为 TARGET。
 * 导入链接分组归属(label_id/import_batch_id)仍只由导入链接业务写,避免进群任务污染导入菜单口径。</p>
 */
@Service
public class GroupLinkRegistryServiceImpl implements GroupLinkRegistryService {

    private static final String SELF_BUILT_LINK_PREFIX = "wa://group/";

    private final GroupLinkMapper groupLinkMapper;
    private final AccountGroupMembershipMapper membershipMapper;

    public GroupLinkRegistryServiceImpl(GroupLinkMapper groupLinkMapper,
                                        AccountGroupMembershipMapper membershipMapper) {
        this.groupLinkMapper = groupLinkMapper;
        this.membershipMapper = membershipMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void registerJoinTaskTargets(List<String> rawLinks) {
        // 先严格归一化并去重,确保 group_link.link_url 的唯一键使用同一套口径。
        Set<String> urls = normalize(rawLinks);
        if (urls.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String url : urls) {
            registerOne(url, now);
        }
    }

    private void registerOne(String url, long now) {
        GroupLink existing = groupLinkMapper.selectAnyByUrl(url);
        if (existing == null) {
            // 全新链接:作为进群任务目标进入群组池,但不归入任何导入链接分组。
            GroupLink row = new GroupLink();
            row.setLinkUrl(url);
            row.setOrigin(GroupLinkOrigin.JOIN_TASK.code());
            row.setMembershipState(GroupMembershipState.TARGET.code());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            groupLinkMapper.insert(row);
            return;
        }
        if (existing.getDeletedAt() != null) {
            // 软删行仍占唯一键,必须复活原行;不复活直接插入会撞唯一键。
            groupLinkMapper.reviveAsStandaloneTarget(existing.getId(), now);
        }
        // 已存在且活跃时故意不改:origin 是首次入池来源,membership_state 只能由后续状态回写升级。
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long registerSelfBuiltGroup(String groupJid,
                                       String groupName,
                                       Long ownerAccountId,
                                       String ownerPhone,
                                       Integer memberCount,
                                       long now) {
        String normalizedJid = normalizeRequired(groupJid, "自建群缺少 groupJid");
        if (ownerAccountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "自建群缺少 ownerAccountId");
        }
        String linkUrl = SELF_BUILT_LINK_PREFIX + normalizedJid;
        GroupLink existing = groupLinkMapper.selectAnyByUrl(linkUrl);
        Long groupLinkId;
        if (existing == null) {
            GroupLink row = new GroupLink();
            row.setLinkUrl(linkUrl);
            row.setGroupName(clamp(blankToNull(groupName), 128));
            row.setOrigin(GroupLinkOrigin.SELF_BUILT.code());
            row.setMembershipState(GroupMembershipState.OWNER.code());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            groupLinkMapper.insert(row);
            groupLinkId = row.getId();
        } else {
            groupLinkId = existing.getId();
            groupLinkMapper.markSelfBuiltGroup(groupLinkId, clamp(blankToNull(groupName), 128), now);
        }
        membershipMapper.upsertPreviewFromAccountSync(
                groupLinkId,
                normalizedJid,
                clamp(blankToNull(groupName), 255),
                memberCount,
                clamp(blankToNull(ownerPhone), 32),
                null,
                null,
                now,
                now);
        AccountGroupMembership membership = new AccountGroupMembership();
        membership.setAccountId(ownerAccountId);
        membership.setGroupLinkId(groupLinkId);
        membership.setGroupJid(normalizedJid);
        membership.setAdmin(true);
        membership.setLastSeenAt(now);
        membership.setCreatedAt(now);
        membership.setUpdatedAt(now);
        membershipMapper.upsertMembership(membership);
        return groupLinkId;
    }

    private static Set<String> normalize(List<String> rawLinks) {
        Set<String> urls = new LinkedHashSet<>();
        if (rawLinks == null || rawLinks.isEmpty()) {
            return urls;
        }
        for (String raw : rawLinks) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            // 进群任务明细负责展示无效链接;群组池登记只收严格合法的群邀请链接。
            GroupLinkUrls.tryNormalize(raw).ifPresent(urls::add);
        }
        return urls;
    }

    private static String normalizeRequired(String value, String message) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
