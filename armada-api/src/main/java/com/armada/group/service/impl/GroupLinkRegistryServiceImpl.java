package com.armada.group.service.impl;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupLinkUrls;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** 自建群在尚未取得邀请链接时使用的内部群入口前缀。 */
    private static final String SELF_BUILT_LINK_PREFIX = "wa://group/";

    /** 群入口数据访问。 */
    private final GroupLinkMapper groupLinkMapper;

    /** 账号在群关系及群信息快照数据访问。 */
    private final AccountGroupMembershipMapper membershipMapper;

    /**
     * 创建群组池登记服务。
     *
     * @param groupLinkMapper 群入口数据访问
     * @param membershipMapper 账号在群关系及群信息快照数据访问
     */
    public GroupLinkRegistryServiceImpl(GroupLinkMapper groupLinkMapper,
                                        AccountGroupMembershipMapper membershipMapper) {
        this.groupLinkMapper = groupLinkMapper;
        this.membershipMapper = membershipMapper;
    }

    /**
     * 登记账号快照或精确关系事件观察到的群。
     *
     * <p>先按群 JID 查询活跃或软删除的群入口并优先复用；未命中时通过内部
     * {@code wa://group/} 链接唯一键原子登记。两条路径都保留首次来源和导入归属，只复活入口、
     * 更新群名和群组池关系态。整个过程不调用协议层。</p>
     *
     * @param groupJid WhatsApp 群 JID，不能为空
     * @param groupName 协议层观察到的群名称，可空
     * @param observedBackend 本次观察群的协议后端
     * @param now 本地登记或复活时间（epoch 毫秒）
     * @return 复用、复活或新建后的 {@code group_link.id}
     * @throws BusinessException 当群 JID 为空时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long registerAccountObservedGroup(String groupJid,
                                             String groupName,
                                             ProtocolBackend observedBackend,
                                             long now) {
        String normalizedJid = normalizeRequired(groupJid, "账号群同步缺少 groupJid");
        String normalizedName = clamp(blankToNull(groupName), 128);
        int syncProtocolMask = observedBackend == ProtocolBackend.ANDROID ? 2 : 1;
        Long groupLinkId = membershipMapper.selectGroupLinkIdByGroupJidIncludingDeleted(normalizedJid);
        if (groupLinkId == null) {
            GroupLink row = new GroupLink();
            row.setLinkUrl(SELF_BUILT_LINK_PREFIX + normalizedJid);
            row.setGroupName(normalizedName == null ? normalizedJid : normalizedName);
            row.setOrigin(GroupLinkOrigin.ACCOUNT_SYNC.code());
            row.setMembershipState(GroupMembershipState.JOINED.code());
            row.setSyncProtocolMask(syncProtocolMask);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            groupLinkMapper.upsertAccountObservedGroup(row, normalizedName);
            GroupLink resolved = groupLinkMapper.selectAnyByUrlForUpdate(row.getLinkUrl());
            if (resolved == null || resolved.getId() == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "账号群入口登记失败");
            }
            return resolved.getId();
        }
        membershipMapper.touchGroupLinkFromAccountSync(
                groupLinkId, normalizedName, syncProtocolMask, now);
        return groupLinkId;
    }

    /**
     * 将进群任务中的有效邀请链接登记到统一群组池。
     *
     * <p>该操作只维护本地群入口；无效链接由进群任务明细保留，不在此处抛错。</p>
     *
     * @param rawLinks 进群任务输入的候选群邀请链接
     */
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
            registerOne(url, now, GroupLinkOrigin.JOIN_TASK);
        }
    }

    /**
     * 把拉群任务冻结的群邀请链接登记为群组池目标，并回填群入口 ID。
     *
     * @param normalizedLinks 已冻结的群邀请链接
     * @param now             登记时间（epoch 毫秒）
     * @return 归一化链接到 {@code group_link.id} 的映射
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Long> registerPullTaskTargets(List<String> normalizedLinks, long now) {
        Map<String, Long> idsByUrl = new LinkedHashMap<>();
        for (String raw : normalizedLinks) {
            GroupLinkUrls.tryNormalize(raw).ifPresent(url ->
                    idsByUrl.computeIfAbsent(url, key -> registerOne(key, now,
                            GroupLinkOrigin.PULL_TASK)));
        }
        return idsByUrl;
    }

    /**
     * 登记或复活单个规范化邀请链接。
     *
     * @param url    已按统一规则规范化的群邀请链接
     * @param now    登记时间（epoch 毫秒）
     * @param origin 首次入池来源；仅在新建时写入，已存在的行不改写
     * @return 复用、复活或新建后的 {@code group_link.id}
     */
    private Long registerOne(String url, long now, GroupLinkOrigin origin) {
        GroupLink existing = groupLinkMapper.selectAnyByUrl(url);
        if (existing == null) {
            // 全新链接:作为任务目标进入群组池,但不归入任何导入链接分组。
            GroupLink row = new GroupLink();
            row.setLinkUrl(url);
            row.setOrigin(origin.code());
            row.setMembershipState(GroupMembershipState.TARGET.code());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            groupLinkMapper.insert(row);
            return row.getId();
        }
        if (existing.getDeletedAt() != null) {
            // 软删行仍占唯一键,必须复活原行;不复活直接插入会撞唯一键。
            groupLinkMapper.reviveAsStandaloneTarget(existing.getId(), now);
        }
        // 已存在且活跃时故意不改:origin 是首次入池来源,membership_state 只能由后续状态回写升级。
        return existing.getId();
    }

    /**
     * 登记业务流程刚创建成功的自建群及建群账号在群关系。
     *
     * <p>同一群 JID 重复登记时复用已有群入口，并刷新群名称、人数和建群账号管理员关系；
     * 本方法不调用协议层，也不获取邀请链接。</p>
     *
     * @param groupJid WhatsApp 群 JID，不能为空
     * @param groupName 建群成功时取得的群名称，可空
     * @param ownerAccountId 建群账号的 Armada 账号 ID，不能为空
     * @param ownerPhone 建群账号手机号，可空
     * @param memberCount 建群完成时取得的群成员数，可空
     * @param now 登记时间（epoch 毫秒）
     * @return 复用或新建后的 {@code group_link.id}
     * @throws BusinessException 当群 JID 或建群账号 ID 缺失时抛出
     */
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
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupLinkId(groupLinkId);
        preview.setGroupJid(normalizedJid);
        preview.setWaSubject(clamp(blankToNull(groupName), 255));
        preview.setMemberSize(memberCount);
        preview.setOwnerPhone(clamp(blankToNull(ownerPhone), 32));
        preview.setOwnerPhoneObserved(true);
        preview.setLastPreviewAt(now);
        preview.setCreatedAt(now);
        preview.setUpdatedAt(now);
        membershipMapper.upsertPreviewFromAccountSync(preview);
        upsertKnownMembership(groupLinkId, normalizedJid, ownerAccountId, true, "SELF_BUILT", now);
        return groupLinkId;
    }

    /**
     * 登记拉群流程已确认的营销账号在群关系。
     *
     * @param groupLinkId 统一群入口 ID，不能为空
     * @param groupJid WhatsApp 群 JID，不能为空
     * @param accountId 已确认进群的 Armada 账号 ID，不能为空
     * @param admin 该账号是否已确认为群管理员
     * @param now 关系确认时间（epoch 毫秒）
     * @throws BusinessException 当群入口、群 JID 或账号 ID 缺失时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void registerKnownMembership(
            Long groupLinkId,
            String groupJid,
            Long accountId,
            boolean admin,
            long now) {
        if (groupLinkId == null || accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "登记在群关系缺少账号或群入口");
        }
        upsertKnownMembership(
                groupLinkId,
                normalizeRequired(groupJid, "登记在群关系缺少 groupJid"),
                accountId,
                admin,
                "GROUP_PULL_MARKETING",
                now);
    }

    /**
     * 幂等写入账号已在群内的关系事实。
     *
     * @param groupLinkId 统一群入口 ID
     * @param groupJid WhatsApp 群 JID
     * @param accountId Armada 账号 ID
     * @param admin 是否为群管理员
     * @param source 关系事实来源
     * @param now 关系确认时间（epoch 毫秒）
     */
    private void upsertKnownMembership(
            Long groupLinkId,
            String groupJid,
            Long accountId,
            boolean admin,
            String source,
            long now) {
        AccountGroupMembership membership = new AccountGroupMembership();
        membership.setAccountId(accountId);
        membership.setGroupLinkId(groupLinkId);
        membership.setGroupJid(groupJid);
        membership.setAdmin(admin);
        membership.setMembershipStatus(AccountGroupMembershipStatus.IN_GROUP.code());
        membership.setStatusSource(source);
        membership.setStatusUpdatedAt(now);
        membership.setJoinedAt(now);
        membership.setLastSeenAt(now);
        membership.setCreatedAt(now);
        membership.setUpdatedAt(now);
        membershipMapper.upsertMembership(membership);
    }

    /**
     * 按群邀请链接统一口径清洗并稳定去重。
     *
     * @param rawLinks 原始候选链接
     * @return 按首次出现顺序排列的合法规范化链接
     */
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

    /**
     * 清理必填文本并在缺失时抛出业务校验异常。
     *
     * @param value 待清理文本
     * @param message 文本缺失时的业务提示
     * @return 去除首尾空白后的文本
     * @throws BusinessException 当文本为空时抛出
     */
    private static String normalizeRequired(String value, String message) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
        return normalized;
    }

    /**
     * 将空白文本统一转换为空值。
     *
     * @param value 待清理文本
     * @return 去除首尾空白后的文本；无有效内容时返回 {@code null}
     */
    private static String blankToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * 按数据库列长度安全截断可选文本。
     *
     * @param value 待截断文本，可空
     * @param maxLength 最大字符数
     * @return 原文本或截断后的文本
     */
    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
