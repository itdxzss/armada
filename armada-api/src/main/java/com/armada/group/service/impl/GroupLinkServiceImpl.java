package com.armada.group.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupAnnouncementTextCommandDTO;
import com.armada.group.model.dto.GroupDescriptionCommandDTO;
import com.armada.group.model.dto.GroupLinkProfileDTO;
import com.armada.group.model.dto.GroupLinkPreviewDTO;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.dto.GroupPictureCommandDTO;
import com.armada.group.model.dto.GroupSubjectCommandDTO;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.model.vo.GroupLinkMemberListVO;
import com.armada.group.model.vo.GroupLinkMemberVO;
import com.armada.group.model.vo.GroupLinkPreviewBatchVO;
import com.armada.group.model.vo.GroupLinkPreviewItemVO;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.model.vo.GroupMemberLookupTarget;
import com.armada.group.model.vo.GroupMemberQueryAccount;
import com.armada.group.service.GroupLinkService;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupProfilePort;
import com.armada.platform.protocol.model.result.GroupPreviewResult;
import com.armada.platform.protocol.port.GroupPreviewPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 群链接业务实现。
 *
 * <p>租户隔离由 MyBatis 租户拦截器透明完成,本类不手写 tenant_id。</p>
 */
@Service
public class GroupLinkServiceImpl implements GroupLinkService {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkServiceImpl.class);

    /** 批量操作上限:防止一次操作过多造成锁竞争。 */
    private static final int BATCH_MAX = 100;

    /** group_link.group_name 列长度。 */
    private static final int GROUP_NAME_MAX_LENGTH = 128;

    /** WhatsApp 群名称协议层最大长度。 */
    private static final int GROUP_SUBJECT_MAX_LENGTH = 100;

    /** WhatsApp 群描述最大长度。 */
    private static final int GROUP_DESCRIPTION_MAX_LENGTH = 512;

    /** WhatsApp 公告文本最大长度。 */
    private static final int GROUP_ANNOUNCEMENT_TEXT_MAX_LENGTH = 512;

    /** group_link.remark 列长度。 */
    private static final int REMARK_MAX_LENGTH = 255;

    /** group_link_preview.avatar_url 列长度。 */
    private static final int AVATAR_URL_MAX_LENGTH = 512;

    /** 群组列表状态筛选白名单。空值表示不筛选状态。 */
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "UNCHECKED", "AVAILABLE", "BANNED", "LINK_INVALID", "UNAVAILABLE");

    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkPreviewMapper previewMapper;
    private final GroupLinkHealthMapper healthMapper;
    private final GroupLinkLabelMapper labelMapper;
    private final AccountGroupMembershipMapper membershipMapper;
    private final GroupConverter converter;
    private final AccountMapper accountMapper;
    private final GroupPreviewPort groupPreviewPort;
    private final GroupParticipantPort groupParticipantPort;
    private final GroupProfilePort groupProfilePort;

    public GroupLinkServiceImpl(GroupLinkMapper groupLinkMapper,
                                GroupLinkPreviewMapper previewMapper,
                                GroupLinkHealthMapper healthMapper,
                                GroupLinkLabelMapper labelMapper,
                                AccountGroupMembershipMapper membershipMapper,
                                GroupConverter converter,
                                AccountMapper accountMapper,
                                GroupPreviewPort groupPreviewPort,
                                GroupParticipantPort groupParticipantPort,
                                GroupProfilePort groupProfilePort) {
        this.groupLinkMapper = groupLinkMapper;
        this.previewMapper = previewMapper;
        this.healthMapper = healthMapper;
        this.labelMapper = labelMapper;
        this.membershipMapper = membershipMapper;
        this.converter = converter;
        this.accountMapper = accountMapper;
        this.groupPreviewPort = groupPreviewPort;
        this.groupParticipantPort = groupParticipantPort;
        this.groupProfilePort = groupProfilePort;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:labelId 可选;为空时查当前租户全量群组列表,有值时查该分组下群链接。
     * 先取总数,为 0 时直接返回空页;分页/筛选全部 SQL 下推,不在内存裁剪。</p>
     */
    @Override
    public PageResult<GroupLinkVO> listByLabel(GroupLinkQuery query) {
        validateStatus(query.getStatus());
        long total = groupLinkMapper.countByLabel(query);
        List<GroupLinkVO> rows = total == 0
                ? List.of()
                : converter.toGroupLinkVOList(groupLinkMapper.selectPageByLabel(query));
        log.debug("群链接分页查询 labelId={} total={}", query.getLabelId(), total);
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:只改 Armada 本地展示资料。群名称/备注落到 {@code group_link},头像落到
     * {@code group_link_preview.avatar_url};这里不调用协议层修改 WhatsApp 真实群资料。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(Long id, GroupLinkProfileDTO dto) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接 ID 不能为空");
        }
        if (dto == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群资料更新请求不能为空");
        }
        if (!hasProfileField(dto)) {
            throw new BusinessException(ErrorCode.VALIDATION, "至少提交一个字段");
        }

        GroupLink link = groupLinkMapper.selectActiveById(id);
        if (link == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群链接不存在或已删除: " + id);
        }

        String groupName = dto.groupName() == null
                ? link.getGroupName()
                : normalizeProfileField(dto.groupName(), GROUP_NAME_MAX_LENGTH, "群名称");
        String remark = dto.remark() == null
                ? link.getRemark()
                : normalizeProfileField(dto.remark(), REMARK_MAX_LENGTH, "备注");
        String avatarUrl = dto.avatarUrl() == null
                ? null
                : normalizeProfileField(dto.avatarUrl(), AVATAR_URL_MAX_LENGTH, "头像URL");

        long now = System.currentTimeMillis();
        int updated = groupLinkMapper.updateProfile(id, groupName, remark, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群链接不存在或已删除: " + id);
        }
        if (dto.avatarUrl() != null) {
            previewMapper.upsertAvatarUrl(id, avatarUrl, now);
        }
        log.info("群链接本地资料更新 groupLinkId={} groupNameUpdated={} remarkUpdated={} avatarUpdated={}",
                id, dto.groupName() != null, dto.remark() != null, dto.avatarUrl() != null);
    }

    /**
     * 修改 WhatsApp 真实群名称。
     *
     * <p>该入口不同于 {@link #updateProfile(Long, GroupLinkProfileDTO)}:这里会真实调用协议层修改
     * WhatsApp 群 subject。协议调用成功后,再把 {@code group_link.group_name} 更新为本地展示镜像;
     * 本地只更新群名,不触碰备注,避免并发覆盖运营备注。</p>
     */
    @Override
    public void updateSubject(Long id, GroupSubjectCommandDTO dto) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群名称更新请求不能为空");
        }
        String subject = requireProfileField(dto.subject(), GROUP_SUBJECT_MAX_LENGTH, "群名称");
        GroupProfileTarget target = resolveGroupProfileTarget(id, dto.accountId());

        // 不包 DB 事务跨外部 HTTP 调用:先确认 WhatsApp 侧成功,再写本地展示镜像。
        groupProfilePort.updateSubject(target.protocolAccountId(), target.groupJid(), subject);
        int updated = groupLinkMapper.updateGroupName(id, subject, System.currentTimeMillis());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群链接不存在或已删除: " + id);
        }
        log.info("WhatsApp 群名称已更新 groupLinkId={} groupJid={} accountId={}",
                id, target.groupJid(), target.accountId());
    }

    /**
     * 修改 WhatsApp 真实群描述。
     *
     * <p>Armada 当前没有群描述本地字段,因此该方法只负责调用协议层。空字符串会在本地归一为
     * {@code null},协议层再将其解释为清空描述。</p>
     */
    @Override
    public void updateDescription(Long id, GroupDescriptionCommandDTO dto) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群描述更新请求不能为空");
        }
        String description = normalizeProfileField(dto.description(), GROUP_DESCRIPTION_MAX_LENGTH, "群描述");
        GroupProfileTarget target = resolveGroupProfileTarget(id, dto.accountId());

        // description 可为 null,用于表达清空群描述;其它群资料字段仍按非空校验处理。
        groupProfilePort.updateDescription(target.protocolAccountId(), target.groupJid(), description);
        log.info("WhatsApp 群描述已更新 groupLinkId={} groupJid={} accountId={} cleared={}",
                id, target.groupJid(), target.accountId(), description == null);
    }

    /**
     * 修改 WhatsApp 群公告文本。
     *
     * <p>协议层当前把公告文本落到群 description 并发布 metadata_updated 事件。Armada 本地暂不持久化
     * 公告文本,避免在没有明确字段契约前复制一份含义不稳定的状态。</p>
     */
    @Override
    public void updateAnnouncementText(Long id, GroupAnnouncementTextCommandDTO dto) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群公告更新请求不能为空");
        }
        String text = requireProfileField(dto.text(), GROUP_ANNOUNCEMENT_TEXT_MAX_LENGTH, "群公告");
        GroupProfileTarget target = resolveGroupProfileTarget(id, dto.accountId());

        // 公告文本必须非空;清空/删除公告若后续需要,应按独立产品语义再加接口。
        groupProfilePort.updateAnnouncementText(target.protocolAccountId(), target.groupJid(), text);
        log.info("WhatsApp 群公告已更新 groupLinkId={} groupJid={} accountId={}",
                id, target.groupJid(), target.accountId());
    }

    /**
     * 修改 WhatsApp 真实群头像。
     *
     * <p>请求支持 URL 或 base64 二选一。URL 形态可作为列表头像展示镜像写入
     * {@code group_link_preview.avatar_url};base64 没有可复用的公开 URL,因此只调用协议层,不落本地头像。</p>
     */
    @Override
    public void updatePicture(Long id, GroupPictureCommandDTO dto) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群头像更新请求不能为空");
        }
        PictureInput picture = normalizePicture(dto);
        GroupProfileTarget target = resolveGroupProfileTarget(id, dto.accountId());

        // 协议成功后再写本地 URL 镜像;协议失败时不要让列表显示一个实际未生效的头像。
        groupProfilePort.updatePicture(target.protocolAccountId(), target.groupJid(), picture.url(), picture.base64());
        if (picture.url() != null) {
            previewMapper.upsertAvatarUrl(id, picture.url(), System.currentTimeMillis());
        }
        log.info("WhatsApp 群头像已更新 groupLinkId={} groupJid={} accountId={} source={}",
                id, target.groupJid(), target.accountId(), picture.url() == null ? "base64" : "url");
    }

    private static void validateStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return;
        }
        if (!ALLOWED_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.VALIDATION, "status 非法: " + status);
        }
    }

    private static boolean hasProfileField(GroupLinkProfileDTO dto) {
        return dto.groupName() != null || dto.remark() != null || dto.avatarUrl() != null;
    }

    private static String normalizeProfileField(String value, int maxLength, String fieldName) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, fieldName + "长度不能超过" + maxLength);
        }
        return normalized;
    }

    private static String requireProfileField(String value, int maxLength, String fieldName) {
        String normalized = normalizeProfileField(value, maxLength, fieldName);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.VALIDATION, fieldName + "不能为空");
        }
        return normalized;
    }

    /**
     * 解析真实群资料操作所需的最小上下文。
     *
     * <p>前端只传本地 {@code group_link.id} 和本地账号 ID。这里统一转换成协议层需要的
     * {@code groupJid + protocolAccountId},并把群 JID 未解析、账号离线等问题提前变成清晰的业务错误。</p>
     */
    private GroupProfileTarget resolveGroupProfileTarget(Long id, Long accountId) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接 ID 不能为空");
        }
        if (accountId == null || accountId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "操作账号 ID 不能为空");
        }
        GroupLink link = groupLinkMapper.selectActiveById(id);
        if (link == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群链接不存在或已删除: " + id);
        }
        // groupJid 是协议层真实操作群的唯一寻址字段;导入链接刚入池时可能还没有解析出来。
        GroupLinkPreview preview = previewMapper.selectByGroupLinkId(id);
        if (preview == null || preview.getGroupJid() == null || preview.getGroupJid().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接尚未解析群 JID,请先预览或等待账号群同步");
        }
        return new GroupProfileTarget(
                preview.getGroupJid().trim(),
                accountId,
                resolveOnlineProtocolAccountId(accountId));
    }

    /** 将本地账号 ID 转成协议账号句柄,并确认账号当前在线。 */
    private String resolveOnlineProtocolAccountId(Long accountId) {
        String protocolAccountId = resolveProtocolAccountId(accountId);
        List<Long> onlineIds = accountMapper.selectOnlineAccountIdsByIds(List.of(accountId), AccountLoginStateCode.ONLINE);
        if (onlineIds == null || !onlineIds.contains(accountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "操作账号未在线: " + accountId);
        }
        return protocolAccountId;
    }

    /**
     * 归一化头像入参。
     *
     * <p>协议层 picture schema 是 {@code image.url? / image.base64?},二者都不能以 null 形式混发。
     * 这里先在业务层收敛为严格二选一,HTTP adapter 再按同样规则构造 wire body。</p>
     */
    private static PictureInput normalizePicture(GroupPictureCommandDTO dto) {
        String url = normalizeProfileField(dto.url(), AVATAR_URL_MAX_LENGTH, "头像URL");
        String base64 = dto.base64() == null || dto.base64().isBlank() ? null : dto.base64().trim();
        if ((url == null && base64 == null) || (url != null && base64 != null)) {
            throw new BusinessException(ErrorCode.VALIDATION, "头像 URL 与 base64 必须二选一");
        }
        return new PictureInput(url, base64);
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:① linkIds 数量须在 1..{@value #BATCH_MAX} 之间、目标分组须存在;
     * ② 迁移前校验 linkIds 全部活跃——活跃数与请求数不一致即整体取消(避免把已删链接误迁);
     * ③ 仅改 group_link.label_id 重挂分组,不动链接的来源批次。全程 {@code @Transactional}。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int migrate(List<Long> linkIds, Long targetLabelId) {
        if (linkIds == null || linkIds.isEmpty() || linkIds.size() > BATCH_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION, "linkIds 数量须为 1.." + BATCH_MAX);
        }
        if (targetLabelId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "目标分组 ID 不能为空");
        }
        // 校验目标分组存在
        if (labelMapper.selectById(targetLabelId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目标分组不存在: " + targetLabelId);
        }
        // 校验 linkIds 全部活跃
        int activeCount = groupLinkMapper.countActiveByIds(linkIds);
        if (activeCount != linkIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "部分群链接不存在或已删除,迁移取消(期望 " + linkIds.size() + " 条,活跃 " + activeCount + " 条)");
        }
        int n = groupLinkMapper.migrateToLabel(linkIds, targetLabelId, System.currentTimeMillis());
        log.info("群链接批量迁移 count={} targetLabelId={}", n, targetLabelId);
        return n;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:先把 Armada 本地 account.id 解析为协议账号句柄,再逐条同步调用协议层
     * group preview HTTP 端口;成功结果立即写入 preview/health 快照表。协议层单条失败只影响该条,
     * 其它链接继续预览。</p>
     */
    @Override
    public GroupLinkPreviewBatchVO previewBatch(GroupLinkPreviewDTO dto) {
        // 入参仍然使用 Armada 本地账号 ID 和 group_link.id,避免前端感知协议层账号句柄。
        List<Long> ids = validatePreviewRequest(dto);
        // 实时预览必须由一个协议账号发起;这里把本地账号解析成协议层 accountId。
        String protocolAccountId = resolveProtocolAccountId(dto.accountId());
        // 先一次性查出活跃链接,后面按请求顺序组装结果;缺失或已删除的 ID 返回单条失败。
        Map<Long, GroupLink> linksById = loadActiveLinks(ids);

        List<GroupLinkPreviewItemVO> items = new ArrayList<>(ids.size());
        int succeeded = 0;
        for (Long id : ids) {
            GroupLink link = linksById.get(id);
            if (link == null) {
                items.add(failedItem(id, null, "群链接不存在或已删除"));
                continue;
            }
            try {
                // 同步 HTTP 链路:Armada -> protocol master -> owner worker -> Baileys/WA。
                // 当前请求内直接拿结果,不走 Kafka/Redis 异步回填。
                GroupPreviewResult preview = groupPreviewPort.preview(protocolAccountId, link.getLinkUrl());
                long previewAt = previewAtMillis(preview.previewAt());
                String ownerPhone = ownerPhone(preview.ownerJid());
                // 成功预览既刷新群元数据快照,也把健康状态重置为可用,供群列表直接读取。
                persistSuccessfulPreview(link, preview, ownerPhone, previewAt);
                items.add(successItem(link, preview, ownerPhone, previewAt));
                succeeded++;
            } catch (ProtocolException ex) {
                // 批量预览是运营工具能力:单条协议失败不应该拖垮整批,因此落到 item.reason。
                log.warn("群链接实时预览失败 groupLinkId={} accountId={} protocolAccountId={} code={} message={}",
                        id, dto.accountId(), protocolAccountId, ex.errorCode(), ex.getMessage());
                items.add(failedItem(id, link.getLinkUrl(), ex.getMessage()));
            }
        }
        int total = ids.size();
        return new GroupLinkPreviewBatchVO(total, succeeded, total - succeeded, List.copyOf(items));
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:成员列表只做实时查询,不落库。查询账号优先来自当前群关系表中仍在线且在群的账号,
     * 这样前端不需要传协议账号句柄,也避免用不在群账号触发协议层失败。</p>
     */
    @Override
    public GroupLinkMemberListVO members(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接 ID 不能为空");
        }
        GroupMemberLookupTarget target = groupLinkMapper.selectMemberLookupTarget(id);
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群链接不存在或已删除: " + id);
        }
        if (target.groupJid() == null || target.groupJid().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接尚未解析群 JID,请先预览或等待账号群同步");
        }
        GroupMemberQueryAccount account = membershipMapper.selectOnlineMemberQueryAccount(
                target.groupLinkId(), AccountLoginStateCode.ONLINE);
        if (account == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "暂无可用在线账号查询成员");
        }
        List<GroupLinkMemberVO> members = groupParticipantPort
                .listParticipants(account.protocolAccountId(), target.groupJid())
                .stream()
                .map(GroupLinkServiceImpl::memberVO)
                .toList();
        log.info("群成员实时查询完成 groupLinkId={} groupJid={} accountId={} total={}",
                target.groupLinkId(), target.groupJid(), account.accountId(), members.size());
        return new GroupLinkMemberListVO(target.groupLinkId(), target.groupJid(), members.size(), members);
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:ids 数量须在 1..{@value #BATCH_MAX} 之间,超限拒绝防锁竞争;
     * 软删(置 deleted_at)选中的群链接,返回实际软删行数。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > BATCH_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION, "ids 数量须为 1.." + BATCH_MAX);
        }
        int n = groupLinkMapper.softDeleteByIds(ids, System.currentTimeMillis());
        log.info("群链接批量删除 count={} ids={}", n, ids);
        return n;
    }

    /**
     * 校验实时预览请求的业务边界。
     *
     * <p>这里不去重,是为了保持返回 items 与前端提交 ids 的顺序和数量一致。</p>
     */
    private static List<Long> validatePreviewRequest(GroupLinkPreviewDTO dto) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "预览请求不能为空");
        }
        if (dto.accountId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
        }
        List<Long> ids = dto.ids();
        if (ids == null || ids.isEmpty() || ids.size() > BATCH_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION, "ids 数量须为 1.." + BATCH_MAX);
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION, "ids 不能包含空值或非法 ID");
        }
        return ids;
    }

    /**
     * 把前端传入的 Armada 本地账号 ID 转为协议层账号句柄。
     *
     * <p>协议层 master/worker 只认识 {@code protocol_account_id},不能直接使用本地自增主键。</p>
     */
    private String resolveProtocolAccountId(Long accountId) {
        Account account = accountMapper.selectActiveById(accountId);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账号不存在或已删除: " + accountId);
        }
        String protocolAccountId = account.getProtocolAccountId();
        if (protocolAccountId == null || protocolAccountId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号未绑定协议账号: " + accountId);
        }
        return protocolAccountId;
    }

    /**
     * 批量加载活跃群链接并转成按 ID 访问的 map。
     *
     * <p>MyBatis 租户拦截器会自动补 tenant_id,这里不手写租户条件。</p>
     */
    private Map<Long, GroupLink> loadActiveLinks(List<Long> ids) {
        List<GroupLink> links = groupLinkMapper.selectActiveByIds(ids);
        Map<Long, GroupLink> linksById = new HashMap<>(links.size());
        for (GroupLink link : links) {
            linksById.put(link.getId(), link);
        }
        return linksById;
    }

    /**
     * 持久化一次成功的实时预览。
     *
     * <p>group_link_preview 保存协议层解析出的群资料;group_link_health 保存运营列表的健康口径。
     * 预览成功即可认为链接当前可用,因此失败原因和连续失败次数都会被清空。</p>
     */
    private void persistSuccessfulPreview(
            GroupLink link,
            GroupPreviewResult preview,
            String ownerPhone,
            long previewAt) {
        long now = System.currentTimeMillis();

        // 预览快照是群资料的事实来源:列表页群名、JID、人数、群主、发言模式都来自这里。
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(link.getId());
        row.setGroupJid(preview.groupJid());
        row.setInviteCode(preview.inviteCode());
        row.setWaSubject(preview.subject());
        row.setMemberSize(preview.memberCount());
        row.setOwnerPhone(ownerPhone);
        row.setAnnounceOnly(preview.announce());
        row.setLastPreviewAt(previewAt);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        previewMapper.upsert(row);

        // 健康表用于状态筛选;预览成功后收敛到 AVAILABLE,避免旧失败状态继续展示。
        GroupLinkHealth health = new GroupLinkHealth();
        health.setGroupLinkId(link.getId());
        health.setHealthStatus(GroupLinkHealthStatus.AVAILABLE.code());
        health.setBanned(preview.banned());
        health.setCurrentCount(preview.memberCount());
        health.setLastCheckAt(previewAt);
        health.setLastHealthError(null);
        health.setHealthFailureCount(0);
        health.setCreatedAt(now);
        health.setUpdatedAt(now);
        healthMapper.upsert(health);
    }

    /** 构造单条成功返回,字段尽量对齐 preview/health 快照,便于前端即时刷新列表行。 */
    private static GroupLinkPreviewItemVO successItem(
            GroupLink link,
            GroupPreviewResult preview,
            String ownerPhone,
            long previewAt) {
        return new GroupLinkPreviewItemVO(
                link.getId(),
                link.getLinkUrl(),
                true,
                null,
                preview.groupJid(),
                preview.subject(),
                preview.memberCount(),
                preview.banned(),
                ownerPhone,
                preview.announce(),
                preview.inviteCode(),
                previewAt);
    }

    /** 构造单条失败返回;失败不写快照表,只把原因返回给当前调用方。 */
    private static GroupLinkPreviewItemVO failedItem(Long groupLinkId, String url, String reason) {
        return new GroupLinkPreviewItemVO(
                groupLinkId,
                url,
                false,
                reason,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** 把协议层成员结果映射成群组明细页 VO。 */
    private static GroupLinkMemberVO memberVO(GroupParticipantResult participant) {
        return new GroupLinkMemberVO(
                participant.jid(),
                participant.phone(),
                participant.admin(),
                participant.owner(),
                participant.role());
    }

    /** 协议层没带 previewAt 时用 Armada 当前时间兜底,保证落库时间轴非空。 */
    private static long previewAtMillis(Instant previewAt) {
        return previewAt == null ? System.currentTimeMillis() : previewAt.toEpochMilli();
    }

    /**
     * 从 WhatsApp JID 里提取纯手机号。
     *
     * <p>协议层常返回 {@code 8613xxx@s.whatsapp.net} 或带设备后缀的 JID,列表筛选只需要号码部分。</p>
     */
    private static String ownerPhone(String ownerJid) {
        if (ownerJid == null || ownerJid.isBlank()) {
            return null;
        }
        String normalized = ownerJid.trim();
        int at = normalized.indexOf('@');
        if (at >= 0) {
            normalized = normalized.substring(0, at);
        }
        int device = normalized.indexOf(':');
        if (device >= 0) {
            normalized = normalized.substring(0, device);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private record GroupProfileTarget(
            String groupJid,
            Long accountId,
            String protocolAccountId) {
    }

    private record PictureInput(String url, String base64) {
    }
}
