package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.mapper.WhatsappGroupMemberSnapshotMapper;
import com.armada.group.model.dto.GroupMemberBatchCommandDTO;
import com.armada.group.model.dto.GroupSettingCommandDTO;
import com.armada.group.model.dto.GroupSubjectCommandDTO;
import com.armada.group.model.dto.GroupTimedMessageCommandDTO;
import com.armada.group.model.enums.GroupPermissionKey;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.enums.GroupTimedMessageMode;
import com.armada.group.model.vo.GroupAvatarUpdateVO;
import com.armada.group.model.vo.GroupDetailVO;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.model.vo.GroupLinkMemberListVO;
import com.armada.group.model.vo.GroupLinkMemberVO;
import com.armada.group.model.vo.GroupMemberBatchResultVO;
import com.armada.group.model.vo.GroupMemberOperationResultVO;
import com.armada.group.model.vo.GroupMetadataSyncAcceptedVO;
import com.armada.group.service.GroupDetailService;
import com.armada.group.service.GroupDetailProtocolPorts;
import com.armada.group.service.GroupDetailSnapshotReader;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.model.result.GroupPictureResult;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 群详情抽屉业务的默认编排实现。
 *
 * <p>详情 GET 只读取 Armada 本地最后成功 metadata 与完整成员快照，不在页面加载时调用协议层。
 * 尚无完整快照时明确返回待同步状态，避免把空成员数组误认为群内无人。</p>
 *
 * <p>群资料、限时消息、权限和成员写操作均由后端自动选号。协议调用超时时不会换号重试，
 * 而是使用同一账号回读 WhatsApp 状态确认结果，避免同一操作被不同账号重复执行。
 * 成员批量操作保留逐 JID 结果，成功项不因其它成员失败而回滚。</p>
 */
@Service
public class GroupDetailServiceImpl implements GroupDetailService {

    /** 当前服务的业务日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(GroupDetailServiceImpl.class);

    /** 群头像文件最大允许大小，避免把超大图片编码后发送到协议层。 */
    private static final long MAX_AVATAR_BYTES = 5L * 1024 * 1024;

    /** WhatsApp 群名称业务允许的最大字符数。 */
    private static final int SUBJECT_MAX_LENGTH = 100;

    /** 单次成员批量操作允许的最大目标数量。 */
    private static final int MEMBER_BATCH_MAX_SIZE = 50;

    /** 图片文件 MIME 类型前缀。 */
    private static final String IMAGE_MIME_PREFIX = "image/";

    /** 协议层确认成员动作已经生效。 */
    private static final String MEMBER_STATUS_OK = "OK";

    /** 协议结果缺失或超时回读无法确认成员状态。 */
    private static final String MEMBER_STATUS_UNKNOWN = "UNKNOWN";

    /** 目标成员是群主，业务层禁止对其执行升降级或移除。 */
    private static final String MEMBER_STATUS_OWNER_PROTECTED = "OWNER_PROTECTED";

    /** 目标 JID 不在当前实时群成员快照中。 */
    private static final String MEMBER_STATUS_NOT_FOUND = "MEMBER_NOT_FOUND";

    /** 成员隐私设置阻止协议动作。 */
    private static final String MEMBER_STATUS_PRIVACY_BLOCKED = "PRIVACY_BLOCKED";

    /** 协议层逐成员动作超时。 */
    private static final String MEMBER_STATUS_TIMEOUT = "TIMEOUT";

    /** 目标成员已经处于请求状态。 */
    private static final String MEMBER_STATUS_ALREADY_IN = "ALREADY_IN";

    /** WhatsApp 群成员数量已满。 */
    private static final String MEMBER_STATUS_GROUP_FULL = "GROUP_FULL";

    /** 群链接本地资料 Mapper。 */
    private final GroupLinkMapper groupLinkMapper;

    /** 群 JID、WhatsApp subject 和头像镜像 Mapper。 */
    private final GroupLinkPreviewMapper previewMapper;

    /** 写操作所用的在线、在群且优先管理员执行账号选择器。 */
    private final GroupExecutionAccountSelector selector;

    /** 群详情业务使用的四类协议能力端口。 */
    private final GroupDetailProtocolPorts protocolPorts;

    /** 最后成功 metadata 和完整成员快照读取器。 */
    private final GroupDetailSnapshotReader snapshotReader;

    /** 群成员最后成功快照 Mapper。 */
    private final WhatsappGroupMemberSnapshotMapper memberSnapshotMapper;

    /** 群详情异步同步任务状态机。 */
    private final GroupMetadataSyncTaskService metadataSyncTaskService;

    /**
     * 创建群详情业务服务。
     *
     * @param groupLinkMapper 群链接本地资料 Mapper
     * @param previewMapper   群预览和实时标识镜像 Mapper
     * @param selector        群操作执行账号选择器
     * @param protocolPorts   群元数据、资料、设置和成员协议端口集合
     */
    public GroupDetailServiceImpl(
            GroupLinkMapper groupLinkMapper,
            GroupLinkPreviewMapper previewMapper,
            GroupExecutionAccountSelector selector,
            GroupDetailProtocolPorts protocolPorts,
            GroupDetailSnapshotReader snapshotReader,
            WhatsappGroupMemberSnapshotMapper memberSnapshotMapper,
            GroupMetadataSyncTaskService metadataSyncTaskService) {
        this.groupLinkMapper = groupLinkMapper;
        this.previewMapper = previewMapper;
        this.selector = selector;
        this.protocolPorts = protocolPorts;
        this.snapshotReader = snapshotReader;
        this.memberSnapshotMapper = memberSnapshotMapper;
        this.metadataSyncTaskService = metadataSyncTaskService;
    }

    /**
     * 聚合 Armada 本地群资料和最后成功的 WhatsApp metadata 快照。
     *
     * <p>先读取本地群链接与预览镜像，再用自动选出的在群账号读取一次 metadata。
     * 群 JID 未解析、没有可执行账号或协议读取失败时，返回 {@code liveStateAvailable=false}
     * 的降级详情而不是让整个抽屉加载失败；本地备注和头像仍然保留。</p>
     *
     * @param id 群链接 ID
     * @return 包含本地资料、实时权限、限时消息和成员快照的群详情
     * @throws BusinessException 当 ID 无效或群链接不存在时抛出
     */
    @Override
    public GroupDetailVO detail(Long id) {
        GroupTarget target = target(id);
        GroupLinkPreview preview = target.preview();
        GroupMetadataSyncTask task = snapshotReader.task(id);
        String localName = firstText(
                target.link().getGroupName(),
                preview == null ? null : preview.getWaSubject());
        String avatarUrl = preview == null ? null : preview.getAvatarUrl();
        if (preview == null || preview.getMetadataObservedAt() == null) {
            return unavailable(target, localName, avatarUrl, "详情待同步", task);
        }
        List<GroupLinkMemberVO> members = snapshotReader.members(id).stream()
                .map(GroupDetailServiceImpl::memberVO)
                .toList();
        log.debug("群详情本地快照读取成功 groupLinkId={} memberCount={}", id, members.size());
        return new GroupDetailVO(
                id,
                target.groupJid(),
                firstText(preview.getWaSubject(), localName),
                target.link().getRemark(),
                avatarUrl,
                true,
                null,
                GroupTimedMessageMode.fromSeconds(preview.getEphemeralDurationSeconds())
                        .map(GroupTimedMessageMode::wireValue)
                        .orElse(null),
                new GroupDetailVO.Permissions(
                        invert(preview.getAdminOnlyEditInfo()),
                        invert(preview.getAnnounceOnly()),
                        preview.getMemberAddMode(),
                        null,
                        preview.getJoinApprovalMode()),
                new GroupDetailVO.Capabilities(new GroupDetailVO.Capability(
                        false,
                        "本地快照不包含实时协议能力声明")),
                true,
                null,
                members,
                syncStatus(task),
                task == null ? null : task.getLastSuccessAt(),
                task == null ? null : task.getLastErrorMessage());
    }

    /**
     * 查询供成员列表入口使用的最后一次完整成员快照。
     *
     * <p>该入口复用 {@link #detail(Long)} 的本地读取结果，不选择账号或请求协议层。
     * 与可降级的详情不同，成员列表必须有完整快照；快照不可用时显式抛业务异常，
     * 防止前端把空列表误认为群内没有成员。</p>
     *
     * @param id 群链接 ID
     * @return 当前群成员列表和总数
     * @throws BusinessException 当群不存在或实时成员不可用时抛出
     */
    @Override
    public GroupLinkMemberListVO members(Long id) {
        GroupDetailVO detail = detail(id);
        if (!detail.membersAvailable()) {
            throw new BusinessException(ErrorCode.VALIDATION, detail.membersUnavailableReason());
        }
        return new GroupLinkMemberListVO(
                detail.groupLinkId(),
                detail.groupJid(),
                detail.members().size(),
                detail.members());
    }

    @Override
    public GroupMetadataSyncAcceptedVO requestMetadataSync(Long id) {
        target(id);
        metadataSyncTaskService.enqueue(
                id,
                GroupMetadataSyncTrigger.MANUAL_REFRESH,
                System.currentTimeMillis());
        return new GroupMetadataSyncAcceptedVO(true, GroupMetadataSyncStatus.PENDING.name());
    }

    /**
     * 修改 WhatsApp 真实群名称并在成功确认后同步 Armada 本地群名镜像。
     *
     * <p>群名称先做非空和 100 字符限制，再自动选择执行账号。协议超时时使用同一账号
     * 回读 metadata；只有回读 subject 与期望值一致才继续更新本地镜像，避免本地显示一个
     * 实际未生效的群名。日志不记录群名称正文。</p>
     *
     * @param id  群链接 ID
     * @param dto 群名称请求
     * @throws BusinessException 当参数无效、无可执行账号、权限不足、协议结果无法确认或本地群不存在时抛出
     */
    @Override
    public void updateSubject(Long id, GroupSubjectCommandDTO dto) {
        if (dto == null || dto.subject() == null || dto.subject().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群名称不能为空");
        }
        String subject = dto.subject().trim();
        if (subject.length() > SUBJECT_MAX_LENGTH) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "群名称不能超过 " + SUBJECT_MAX_LENGTH + " 个字符");
        }
        GroupTarget target = requireLiveTarget(id);
        GroupExecutionAccount account = selector.require(id);
        try {
            protocolPorts.profile().updateSubject(
                    account.protocolRef(), target.groupJid(), subject);
        } catch (ProtocolException ex) {
            boolean confirmed = ex.errorCode() == ProtocolErrorCode.TIMEOUT
                    && subjectConfirmed(account, target.groupJid(), subject);
            if (!confirmed) {
                log.warn("群名称更新失败 groupLinkId={} accountId={} code={}",
                        id, account.accountId(), ex.errorCode());
                throw profileMutationFailure(ex);
            }
            log.info("群名称协议调用超时后回读确认成功 groupLinkId={} accountId={}",
                    id, account.accountId());
        }
        if (groupLinkMapper.updateGroupName(id, subject, System.currentTimeMillis()) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群链接不存在或已删除: " + id);
        }
        enqueueMetadataRefresh(id);
        log.info("WhatsApp 群名称已更新并同步本地镜像 groupLinkId={} accountId={}",
                id, account.accountId());
    }

    /**
     * 上传图片并修改 WhatsApp 真实群头像，随后尽力同步头像 URL 镜像。
     *
     * <p>仅接受不超过 5 MiB 的图片，编码后的 base64 只发送给协议端口且绝不写日志。
     * 协议超时时使用同一账号读取当前头像 URL；只有 URL 与旧值不同才视为已确认生效。
     * WhatsApp 已应用但协议未返回 URL 时允许 {@code mirrorSynced=false}，由前端提示列表待刷新。</p>
     *
     * @param id   群链接 ID
     * @param file 上传的群头像图片
     * @return WhatsApp 应用状态、本地镜像同步状态和回读头像 URL
     * @throws BusinessException 当文件无效、无可执行账号、权限不足或协议结果无法确认时抛出
     */
    @Override
    public GroupAvatarUpdateVO updateAvatar(Long id, MultipartFile file) {
        validateAvatar(file);
        GroupTarget target = requireLiveTarget(id);
        GroupExecutionAccount account = selector.require(id);
        String base64 = Base64.getEncoder().encodeToString(readBytes(file));
        String oldAvatarUrl = target.preview() == null ? null : target.preview().getAvatarUrl();
        GroupPictureResult result;
        try {
            result = protocolPorts.profile().updatePicture(
                    account.protocolRef(), target.groupJid(), null, base64);
        } catch (ProtocolException ex) {
            String confirmedUrl = ex.errorCode() == ProtocolErrorCode.TIMEOUT
                    ? pictureUrlAfterTimeout(account, target.groupJid(), oldAvatarUrl)
                    : null;
            if (confirmedUrl == null) {
                log.warn("群头像更新失败 groupLinkId={} accountId={} code={}",
                        id, account.accountId(), ex.errorCode());
                throw profileMutationFailure(ex);
            }
            log.info("群头像协议调用超时后回读确认成功 groupLinkId={} accountId={}",
                    id, account.accountId());
            result = new GroupPictureResult(true, confirmedUrl);
        }
        boolean mirrorSynced = result.avatarUrl() != null
                && previewMapper.upsertAvatarUrl(
                        id, result.avatarUrl(), System.currentTimeMillis()) > 0;
        log.info("WhatsApp 群头像更新完成 groupLinkId={} accountId={} applied={} mirrorSynced={}",
                id, account.accountId(), result.applied(), mirrorSynced);
        if (result.applied()) {
            enqueueMetadataRefresh(id);
        }
        return new GroupAvatarUpdateVO(result.applied(), mirrorSynced, result.avatarUrl());
    }

    /**
     * 修改 WhatsApp 群限时消息周期并回读确认。
     *
     * <p>请求只接受关闭、24 小时、7 天和 90 天四档。无论协议写请求直接成功还是超时，
     * 都使用同一执行账号重新读取 metadata，只有实际秒数与期望值一致才返回成功。</p>
     *
     * @param id  群链接 ID
     * @param dto 限时消息模式请求
     * @throws BusinessException 当参数无效、无可执行账号、权限不足或回读状态不一致时抛出
     */
    @Override
    public void updateTimedMessage(Long id, GroupTimedMessageCommandDTO dto) {
        if (dto == null || dto.mode() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择限时消息模式");
        }
        GroupTarget target = requireLiveTarget(id);
        GroupExecutionAccount account = selector.require(id);
        int expectedSeconds = dto.mode().seconds();
        try {
            protocolPorts.settings().setEphemeralDuration(
                    account.protocolRef(), target.groupJid(), expectedSeconds);
        } catch (ProtocolException ex) {
            if (ex.errorCode() != ProtocolErrorCode.TIMEOUT) {
                log.warn("限时消息设置失败 groupLinkId={} accountId={} mode={} code={}",
                        id, account.accountId(), dto.mode(), ex.errorCode());
                throw groupBusinessException(ex);
            }
            log.warn("限时消息协议调用超时，开始同账号回读 groupLinkId={} accountId={} mode={}",
                    id, account.accountId(), dto.mode());
        }
        confirmTimedMessage(account, target.groupJid(), expectedSeconds);
        enqueueMetadataRefresh(id);
        log.info("WhatsApp 群限时消息已更新 groupLinkId={} accountId={} mode={}",
                id, account.accountId(), dto.mode());
    }

    /**
     * 修改一项 WhatsApp 群权限并回读确认。
     *
     * <p>权限 key 使用固定枚举映射协议能力。通过链接邀请会先读取 capability，当前协议版本
     * 不支持时直接返回明确业务错误，不借用添加成员或入群审批接口。协议写入超时时不换号，
     * 仍由同一账号回读对应 metadata 字段确认。</p>
     *
     * @param id  群链接 ID
     * @param dto 权限 key 和期望开关状态
     * @throws BusinessException 当参数无效、能力不支持、权限不足或回读状态不一致时抛出
     */
    @Override
    public void updateSetting(Long id, GroupSettingCommandDTO dto) {
        if (dto == null || dto.key() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群权限设置不能为空");
        }
        GroupTarget target = requireLiveTarget(id);
        GroupExecutionAccount account = selector.require(id);
        ensureSupportedSetting(account, target.groupJid(), dto.key());
        try {
            applySetting(account, target.groupJid(), dto);
        } catch (ProtocolException ex) {
            if (ex.errorCode() != ProtocolErrorCode.TIMEOUT) {
                log.warn("群权限设置失败 groupLinkId={} accountId={} key={} enabled={} code={}",
                        id, account.accountId(), dto.key(), dto.enabled(), ex.errorCode());
                throw groupBusinessException(ex);
            }
            log.warn("群权限协议调用超时，开始同账号回读 groupLinkId={} accountId={} key={} enabled={}",
                    id, account.accountId(), dto.key(), dto.enabled());
        }
        confirmSetting(account, target.groupJid(), dto.key(), dto.enabled());
        persistConfirmedSetting(id, dto.key(), dto.enabled());
        enqueueMetadataRefresh(id);
        log.info("WhatsApp 群权限已更新 groupLinkId={} accountId={} key={} enabled={}",
                id, account.accountId(), dto.key(), dto.enabled());
    }

    /**
     * 把已经由同一账号实时回读确认的权限立即写入本地快照。
     *
     * <p>详情接口只读本地快照；如果仅排队异步 metadata 刷新，前端写成功后立即重载会读到旧值，
     * 导致开关视觉回弹。这里仅写本次已确认字段，并提升观察时间以阻止更早的异步结果覆盖。</p>
     */
    private void persistConfirmedSetting(Long groupLinkId, GroupPermissionKey key, boolean enabled) {
        long now = System.currentTimeMillis();
        int updated = switch (key) {
            case EDIT_GROUP_SETTINGS -> previewMapper.updateAdminOnlyEditInfo(
                    groupLinkId, !enabled, now);
            case SEND_MESSAGES -> previewMapper.updateAnnounceOnly(
                    groupLinkId, !enabled, now);
            case ADD_MEMBERS -> previewMapper.updateMemberAddMode(
                    groupLinkId, enabled, now);
            case ADMIN_APPROVE_NEW_MEMBERS -> previewMapper.updateJoinApprovalMode(
                    groupLinkId, enabled, now);
            case INVITE_VIA_LINK -> 0;
        };
        if (updated == 0 && key != GroupPermissionKey.INVITE_VIA_LINK) {
            log.warn("群权限已确认但本地快照未更新 groupLinkId={} key={}", groupLinkId, key);
        }
    }

    /**
     * 批量提升选中成员为管理员。
     *
     * @param id  群链接 ID
     * @param dto 目标成员 JID 列表，每次 1 到 50 个
     * @return 按请求顺序返回的逐成员结果和部分成功标记
     * @throws BusinessException 当请求无效、无可执行账号或协议调用失败时抛出
     */
    @Override
    public GroupMemberBatchResultVO promoteMembers(
            Long id, GroupMemberBatchCommandDTO dto) {
        return updateMembers(id, dto, GroupParticipantAction.PROMOTE);
    }

    /**
     * 批量取消选中成员的管理员身份。
     *
     * @param id  群链接 ID
     * @param dto 目标成员 JID 列表，每次 1 到 50 个
     * @return 按请求顺序返回的逐成员结果和部分成功标记
     * @throws BusinessException 当请求无效、无可执行账号或协议调用失败时抛出
     */
    @Override
    public GroupMemberBatchResultVO demoteMembers(
            Long id, GroupMemberBatchCommandDTO dto) {
        return updateMembers(id, dto, GroupParticipantAction.DEMOTE);
    }

    /**
     * 批量将选中成员移出 WhatsApp 群。
     *
     * <p>本操作只移除现有成员，不包含添加成员或封禁；群主会在协议调用前被保护并返回逐项原因。</p>
     *
     * @param id  群链接 ID
     * @param dto 目标成员 JID 列表，每次 1 到 50 个
     * @return 按请求顺序返回的逐成员结果和部分成功标记
     * @throws BusinessException 当请求无效、无可执行账号或协议调用失败时抛出
     */
    @Override
    public GroupMemberBatchResultVO kickMembers(
            Long id, GroupMemberBatchCommandDTO dto) {
        return updateMembers(id, dto, GroupParticipantAction.REMOVE);
    }

    /**
     * 使用同一执行账号完成一次成员批量动作并合并业务预校验与协议逐项结果。
     *
     * <p>调用协议前先读取实时 metadata：不在群内的 JID 标记为 MEMBER_NOT_FOUND，群主标记为
     * OWNER_PROTECTED，其余成员才进入协议请求。协议超时时不换号重试，而是用同一账号重新读取
     * metadata，按角色或是否仍在群内判断每个成员是否已经生效。移除动作即使收到协议 OK，
     * 也必须使用同一账号回读确认成员确实已离群，避免把已接收但未生效的协议回执提示为成功。</p>
     *
     * @param id     群链接 ID
     * @param dto    目标成员 JID 请求
     * @param action 升管理员、降管理员或移除动作
     * @return 按原请求顺序合并后的批量结果
     */
    private GroupMemberBatchResultVO updateMembers(
            Long id,
            GroupMemberBatchCommandDTO dto,
            GroupParticipantAction action) {
        List<String> requestedJids = validatedMemberJids(dto);
        GroupTarget target = requireLiveTarget(id);
        GroupExecutionAccount readAccount = selector.require(id);
        GroupMetadataResult metadata;
        try {
            metadata = protocolPorts.metadata().getMetadata(
                    readAccount.protocolRef(), target.groupJid());
        } catch (ProtocolException ex) {
            log.warn("群成员操作前读取元数据失败 groupLinkId={} accountId={} action={} code={}",
                    id, readAccount.accountId(), action, ex.errorCode());
            throw groupBusinessException(ex);
        }
        GroupExecutionAccount account = requireCurrentAdministrator(
                id, readAccount, metadata, requestedJids);
        Map<String, GroupParticipantResult> currentMembers = membersByJid(metadata);
        Map<String, GroupMemberOperationResultVO> fixedResults = new LinkedHashMap<>();
        List<String> actionable = new ArrayList<>();
        for (String jid : requestedJids) {
            GroupParticipantResult member = currentMembers.get(jid);
            if (member == null) {
                fixedResults.put(jid, memberResult(
                        jid,
                        MEMBER_STATUS_NOT_FOUND,
                        ErrorCode.GROUP_MEMBER_NOT_FOUND.defaultMessage()));
            } else if (Boolean.TRUE.equals(member.owner())) {
                fixedResults.put(jid, memberResult(
                        jid,
                        MEMBER_STATUS_OWNER_PROTECTED,
                        ErrorCode.GROUP_OWNER_PROTECTED.defaultMessage()));
            } else {
                actionable.add(jid);
            }
        }
        if (!actionable.isEmpty()) {
            Map<String, GroupMemberOperationResultVO> mutationResults;
            try {
                GroupParticipantBatchResult protocolResult =
                        protocolPorts.participants().updateParticipants(
                                account.protocolRef(),
                                target.groupJid(),
                                actionable,
                                action);
                mutationResults = mapProtocolMemberResults(protocolResult);
                if (action == GroupParticipantAction.REMOVE) {
                    mutationResults = confirmReportedRemovals(
                            account,
                            target.groupJid(),
                            actionable,
                            mutationResults);
                }
            } catch (ProtocolException ex) {
                if (ex.errorCode() != ProtocolErrorCode.TIMEOUT) {
                    log.warn("群成员协议操作失败 groupLinkId={} accountId={} action={} targetCount={} code={}",
                            id, account.accountId(), action, actionable.size(), ex.errorCode());
                    throw groupBusinessException(ex);
                }
                log.warn("群成员协议操作超时，开始同账号回读 groupLinkId={} accountId={} action={} targetCount={}",
                        id, account.accountId(), action, actionable.size());
                mutationResults = confirmTimedOutMembers(
                        account, target.groupJid(), actionable, action);
            }
            fixedResults.putAll(mutationResults);
        }
        GroupMemberBatchResultVO result = summarizeMemberResults(requestedJids, fixedResults);
        List<String> successfulJids = result.results().stream()
                .filter(item -> MEMBER_STATUS_OK.equals(item.status()))
                .map(GroupMemberOperationResultVO::jid)
                .toList();
        long successCount = successfulJids.size();
        if (successCount > 0) {
            persistConfirmedMemberChanges(id, action, successfulJids);
            enqueueMetadataRefresh(id);
        }
        log.info("群成员批量操作完成 groupLinkId={} accountId={} action={} requestedCount={} "
                        + "protocolTargetCount={} successCount={} partial={}",
                id,
                account.accountId(),
                action,
                requestedJids.size(),
                actionable.size(),
                successCount,
                result.partial());
        return result;
    }

    /**
     * 根据本次实时 metadata 选择仍具备管理员权限、且不属于本次操作目标的执行账号。
     *
     * <p>数据库里的管理员标记可能晚于 WhatsApp 实际状态。尤其取消管理员后，如果继续让被
     * 降权账号执行后续提权，WhatsApp 会明确返回 403。这里以本次协议回读为准，并排除所有
     * 目标成员，避免账号对自己执行降权、移除后形成无法恢复的死路。</p>
     */
    private GroupExecutionAccount requireCurrentAdministrator(
            Long groupLinkId,
            GroupExecutionAccount readAccount,
            GroupMetadataResult metadata,
            List<String> targetJids) {
        Set<String> targets = targetJids.stream()
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<String> adminPhones = metadata.participants().stream()
                .filter(member -> Boolean.TRUE.equals(member.admin())
                        || Boolean.TRUE.equals(member.owner()))
                .filter(member -> member.jid() != null
                        && !targets.contains(member.jid().trim().toLowerCase(Locale.ROOT)))
                .map(GroupParticipantResult::phone)
                .map(GroupDetailServiceImpl::normalizedPhone)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        String readPhone = normalizedPhone(readAccount.wsPhone());
        if (readPhone == null && readAccount.groupAdmin()) {
            return readAccount;
        }
        if (readPhone != null && adminPhones.contains(readPhone)) {
            return readAccount;
        }
        return selector.findAdminByPhones(groupLinkId, adminPhones, 0)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.GROUP_PERMISSION_DENIED,
                        "没有其他在线群主或管理员可执行该成员操作"));
    }

    private static String normalizedPhone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        int at = normalized.indexOf('@');
        if (at >= 0 && !normalized.toLowerCase(Locale.ROOT).endsWith("@s.whatsapp.net")) {
            return null;
        }
        String digits = normalized
                .replace("+", "")
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "")
                .replace("@s.whatsapp.net", "");
        if (!digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return digits.isBlank() ? null : digits;
    }

    /**
     * 把协议确认成功的成员变更立即写入本地快照。
     *
     * <p>详情接口只读取本地成员快照，等待异步 metadata 刷新会让操作后的页面继续显示旧角色。
     * 管理员角色和成员移除均可精确更新；添加成员仍以完整 metadata 同步为准。</p>
     */
    private void persistConfirmedMemberChanges(
            Long groupLinkId,
            GroupParticipantAction action,
            List<String> successfulJids) {
        if (action == GroupParticipantAction.REMOVE) {
            memberSnapshotMapper.deleteParticipants(groupLinkId, successfulJids);
            return;
        }
        Boolean admin = switch (action) {
            case PROMOTE -> true;
            case DEMOTE -> false;
            case ADD, REMOVE -> null;
        };
        if (admin == null) {
            return;
        }
        int updated = memberSnapshotMapper.updateAdminRole(
                groupLinkId, successfulJids, admin, System.currentTimeMillis());
        if (updated < successfulJids.size()) {
            log.warn("管理员角色已确认但本地快照未全部更新 groupLinkId={} action={} "
                            + "targetCount={} updatedCount={}",
                    groupLinkId, action, successfulJids.size(), updated);
        }
    }

    private void enqueueMetadataRefresh(Long groupLinkId) {
        metadataSyncTaskService.enqueue(
                groupLinkId,
                GroupMetadataSyncTrigger.METADATA_CHANGED,
                System.currentTimeMillis());
    }

    /**
     * 校验、去空白并按请求顺序去重成员 JID。
     *
     * @param dto 成员批量请求
     * @return 1 到 50 个去重后的成员 JID
     * @throws BusinessException 当列表为空、超过 50 个或包含空 JID 时抛出
     */
    private static List<String> validatedMemberJids(GroupMemberBatchCommandDTO dto) {
        if (dto == null || dto.jids() == null
                || dto.jids().isEmpty() || dto.jids().size() > MEMBER_BATCH_MAX_SIZE) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "群成员操作每次必须选择 1 到 " + MEMBER_BATCH_MAX_SIZE + " 人");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String jid : dto.jids()) {
            if (jid == null || jid.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION, "群成员 JID 不能为空");
            }
            unique.add(jid.trim());
        }
        return List.copyOf(unique);
    }

    /**
     * 将实时成员快照按 JID 建索引，供群主保护和超时确认使用。
     *
     * @param metadata 协议层群元数据
     * @return 忽略空 JID 后的成员索引
     */
    private static Map<String, GroupParticipantResult> membersByJid(
            GroupMetadataResult metadata) {
        Map<String, GroupParticipantResult> members = new LinkedHashMap<>();
        if (metadata.participants() == null) {
            return members;
        }
        for (GroupParticipantResult participant : metadata.participants()) {
            if (participant.jid() != null && !participant.jid().isBlank()) {
                members.put(participant.jid(), participant);
            }
        }
        return members;
    }

    /**
     * 把协议层逐成员状态转换为前端稳定结果。
     *
     * <p>协议漏回 JID 或状态时不猜测成功，后续汇总会为缺失项补 UNKNOWN。</p>
     *
     * @param protocolResult 协议层批量结果
     * @return 按 JID 索引的稳定成员结果
     */
    private static Map<String, GroupMemberOperationResultVO> mapProtocolMemberResults(
            GroupParticipantBatchResult protocolResult) {
        Map<String, GroupMemberOperationResultVO> results = new LinkedHashMap<>();
        if (protocolResult == null || protocolResult.results() == null) {
            return results;
        }
        for (GroupParticipantBatchResult.Item item : protocolResult.results()) {
            if (item.jid() == null || item.jid().isBlank()) {
                continue;
            }
            String status = item.status() == null || item.status().isBlank()
                    ? MEMBER_STATUS_UNKNOWN
                    : item.status();
            results.put(item.jid(), memberResult(
                    item.jid(), status, memberStatusReason(status)));
        }
        return results;
    }

    /**
     * 对协议报告成功的移除成员再次读取 WhatsApp metadata，拒绝假成功回执。
     *
     * <p>只复核协议状态为 OK 的目标，协议明确失败或漏回的结果保持不变。回读失败、
     * 或目标成员仍在群内时把该项降为 UNKNOWN，确保页面不会显示踢出成功。</p>
     *
     * @param account         原执行账号，禁止在确认阶段重新选号
     * @param groupJid        WhatsApp 群 JID
     * @param actionable      实际发送给协议层的成员 JID
     * @param protocolResults 协议逐成员结果
     * @return 回读确认后的逐成员结果
     */
    private Map<String, GroupMemberOperationResultVO> confirmReportedRemovals(
            GroupExecutionAccount account,
            String groupJid,
            List<String> actionable,
            Map<String, GroupMemberOperationResultVO> protocolResults) {
        List<String> reportedSuccesses = actionable.stream()
                .filter(jid -> {
                    GroupMemberOperationResultVO result = protocolResults.get(jid);
                    return result != null && MEMBER_STATUS_OK.equals(result.status());
                })
                .toList();
        if (reportedSuccesses.isEmpty()) {
            return protocolResults;
        }
        Map<String, GroupParticipantResult> currentMembers;
        try {
            currentMembers = membersByJid(protocolPorts.metadata().getMetadata(
                    account.protocolRef(), groupJid));
        } catch (ProtocolException ex) {
            log.warn("群成员移除成功回执复核失败 accountId={} targetCount={} code={}",
                    account.accountId(), reportedSuccesses.size(), ex.errorCode());
            currentMembers = null;
        }
        Map<String, GroupMemberOperationResultVO> confirmed =
                new LinkedHashMap<>(protocolResults);
        int confirmedCount = 0;
        for (String jid : reportedSuccesses) {
            boolean removed = currentMembers != null && !currentMembers.containsKey(jid);
            String status = removed ? MEMBER_STATUS_OK : MEMBER_STATUS_UNKNOWN;
            confirmed.put(jid, memberResult(jid, status, memberStatusReason(status)));
            if (removed) {
                confirmedCount++;
            }
        }
        log.info("群成员移除成功回执复核完成 accountId={} targetCount={} confirmedCount={}",
                account.accountId(), reportedSuccesses.size(), confirmedCount);
        return confirmed;
    }

    /**
     * 在成员写操作超时后使用原执行账号回读 metadata 确认逐项结果。
     *
     * @param account    原执行账号，禁止在确认阶段重新选号
     * @param groupJid   WhatsApp 群 JID
     * @param actionable 实际发送给协议层的成员 JID
     * @param action     原成员动作
     * @return 能确认的成员返回 OK，其余返回 UNKNOWN
     */
    private Map<String, GroupMemberOperationResultVO> confirmTimedOutMembers(
            GroupExecutionAccount account,
            String groupJid,
            List<String> actionable,
            GroupParticipantAction action) {
        GroupMetadataResult confirmed;
        try {
            confirmed = protocolPorts.metadata().getMetadata(
                    account.protocolRef(), groupJid);
        } catch (ProtocolException ex) {
            log.warn("群成员操作超时回读失败 accountId={} action={} targetCount={} code={}",
                    account.accountId(), action, actionable.size(), ex.errorCode());
            return unknownMemberResults(actionable);
        }
        Map<String, GroupParticipantResult> current = membersByJid(confirmed);
        Map<String, GroupMemberOperationResultVO> results = new LinkedHashMap<>();
        for (String jid : actionable) {
            String status = mutationConfirmed(current.get(jid), action)
                    ? MEMBER_STATUS_OK
                    : MEMBER_STATUS_UNKNOWN;
            results.put(jid, memberResult(jid, status, memberStatusReason(status)));
        }
        long confirmedCount = results.values().stream()
                .filter(item -> MEMBER_STATUS_OK.equals(item.status()))
                .count();
        log.info("群成员操作超时回读完成 accountId={} action={} targetCount={} confirmedCount={}",
                account.accountId(), action, actionable.size(), confirmedCount);
        return results;
    }

    /**
     * 判断回读成员状态是否证明指定动作已经生效。
     *
     * @param current 回读后的当前成员；移除成功时允许为空
     * @param action  原成员动作
     * @return true 表示 metadata 已证明动作生效
     */
    private static boolean mutationConfirmed(
            GroupParticipantResult current,
            GroupParticipantAction action) {
        return switch (action) {
            case ADD -> current != null;
            case PROMOTE -> current != null
                    && (Boolean.TRUE.equals(current.admin())
                    || Boolean.TRUE.equals(current.owner()));
            case DEMOTE -> current != null
                    && !Boolean.TRUE.equals(current.admin())
                    && !Boolean.TRUE.equals(current.owner());
            case REMOVE -> current == null;
        };
    }

    /**
     * 为无法回读确认的成员构造 UNKNOWN 结果。
     *
     * @param jids 待确认成员 JID
     * @return 按 JID 索引的 UNKNOWN 结果
     */
    private static Map<String, GroupMemberOperationResultVO> unknownMemberResults(
            List<String> jids) {
        Map<String, GroupMemberOperationResultVO> results = new LinkedHashMap<>();
        for (String jid : jids) {
            results.put(jid, memberResult(
                    jid, MEMBER_STATUS_UNKNOWN, memberStatusReason(MEMBER_STATUS_UNKNOWN)));
        }
        return results;
    }

    /**
     * 按原请求顺序汇总预校验结果和协议结果。
     *
     * <p>协议漏回的成员统一补 UNKNOWN；只有全部成员状态都是 OK 时 {@code ok=true}，
     * 任意失败或未知都会设置 partial，成功项不回滚。</p>
     *
     * @param requestedJids 原请求顺序的成员 JID
     * @param knownResults  已知的预校验或协议结果
     * @return 前端批量操作结果
     */
    private static GroupMemberBatchResultVO summarizeMemberResults(
            List<String> requestedJids,
            Map<String, GroupMemberOperationResultVO> knownResults) {
        List<GroupMemberOperationResultVO> results = requestedJids.stream()
                .map(jid -> knownResults.getOrDefault(
                        jid,
                        memberResult(
                                jid,
                                MEMBER_STATUS_UNKNOWN,
                                memberStatusReason(MEMBER_STATUS_UNKNOWN))))
                .toList();
        long succeeded = results.stream()
                .filter(result -> MEMBER_STATUS_OK.equals(result.status()))
                .count();
        boolean ok = succeeded == results.size();
        String message = ok
                ? "成员操作成功"
                : succeeded == 0 ? "成员操作未完成" : "部分成员操作成功";
        return new GroupMemberBatchResultVO(ok, !ok, message, results);
    }

    /**
     * 构造单个成员的稳定操作结果。
     *
     * @param jid    成员 JID
     * @param status 稳定状态码
     * @param reason 面向前端的失败原因，成功时为空
     * @return 单成员结果
     */
    private static GroupMemberOperationResultVO memberResult(
            String jid, String status, String reason) {
        return new GroupMemberOperationResultVO(jid, status, reason);
    }

    /**
     * 将协议层稳定状态转换为面向运营的失败原因。
     *
     * @param status 协议或本地稳定状态
     * @return 成功时返回 null，其余返回可展示原因
     */
    private static String memberStatusReason(String status) {
        return switch (status) {
            case MEMBER_STATUS_OK -> null;
            case MEMBER_STATUS_UNKNOWN -> "操作结果待确认，请刷新";
            case MEMBER_STATUS_PRIVACY_BLOCKED -> "成员隐私设置阻止了本次操作";
            case MEMBER_STATUS_TIMEOUT -> "WhatsApp 操作超时";
            case MEMBER_STATUS_ALREADY_IN -> "成员当前状态已存在";
            case MEMBER_STATUS_GROUP_FULL -> "群成员数量已满";
            default -> "WhatsApp 未完成该成员操作";
        };
    }

    /**
     * 加载一个未删除群链接及其可选预览镜像。
     *
     * <p>groupJid 只来源于已落库的预览数据；链接尚未预览时允许为空，供详情读取走降级路径。</p>
     *
     * @param id 群链接 ID
     * @return 本地群链接、预览镜像和归一化群 JID
     * @throws BusinessException 当 ID 无效或群链接不存在时抛出
     */
    private GroupTarget target(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接 ID 不能为空");
        }
        GroupLink link = groupLinkMapper.selectActiveById(id);
        if (link == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "群链接不存在或已删除: " + id);
        }
        GroupLinkPreview preview = previewMapper.selectByGroupLinkId(id);
        String groupJid = preview == null || preview.getGroupJid() == null
                || preview.getGroupJid().isBlank()
                ? null
                : preview.getGroupJid().trim();
        return new GroupTarget(link, preview, groupJid);
    }

    /**
     * 加载必须具备真实群 JID 的写操作目标。
     *
     * @param id 群链接 ID
     * @return 包含非空 groupJid 的操作目标
     * @throws BusinessException 当群链接不存在或尚未解析群 JID 时抛出
     */
    private GroupTarget requireLiveTarget(Long id) {
        GroupTarget target = target(id);
        if (target.groupJid() == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "群链接尚未解析群 JID，请先预览或等待账号群同步");
        }
        return target;
    }

    /**
     * 在群名称写操作超时后使用同一账号回读 subject。
     *
     * @param account         原执行账号
     * @param groupJid        WhatsApp 群 JID
     * @param expectedSubject 期望群名称，仅用于内存比较，不写日志
     * @return true 表示回读群名称与期望值完全一致
     */
    private boolean subjectConfirmed(
            GroupExecutionAccount account,
            String groupJid,
            String expectedSubject) {
        try {
            GroupMetadataResult metadata = protocolPorts.metadata().getMetadata(
                    account.protocolRef(), groupJid);
            return expectedSubject.equals(metadata.subject());
        } catch (ProtocolException readEx) {
            log.warn("群名称超时回读失败 accountId={} code={}",
                    account.accountId(), readEx.errorCode());
            return false;
        }
    }

    /**
     * 在群头像写操作超时后使用同一账号回读头像 URL。
     *
     * <p>只有回读到非空且不同于旧镜像的 URL 才能证明本次头像操作已生效。</p>
     *
     * @param account      原执行账号
     * @param groupJid     WhatsApp 群 JID
     * @param oldAvatarUrl 写操作前的本地头像 URL 镜像
     * @return 可确认的新头像 URL；无法确认时返回 null
     */
    private String pictureUrlAfterTimeout(
            GroupExecutionAccount account,
            String groupJid,
            String oldAvatarUrl) {
        try {
            String current = protocolPorts.profile().getPictureUrl(
                    account.protocolRef(), groupJid);
            return current == null || current.isBlank() || current.equals(oldAvatarUrl)
                    ? null
                    : current.trim();
        } catch (ProtocolException readEx) {
            log.warn("群头像超时回读失败 accountId={} code={}",
                    account.accountId(), readEx.errorCode());
            return null;
        }
    }

    /**
     * 回读并确认限时消息实际秒数。
     *
     * @param account         原执行账号
     * @param groupJid        WhatsApp 群 JID
     * @param expectedSeconds 期望限时消息秒数
     * @throws BusinessException 当回读失败或实际值不一致时抛出待确认错误
     */
    private void confirmTimedMessage(
            GroupExecutionAccount account,
            String groupJid,
            int expectedSeconds) {
        try {
            GroupMetadataResult metadata = protocolPorts.metadata().getMetadata(
                    account.protocolRef(), groupJid);
            if (Integer.valueOf(expectedSeconds).equals(
                    metadata.ephemeralDurationSeconds())) {
                return;
            }
            log.warn("限时消息设置回读状态不一致 accountId={} expectedSeconds={} actualSeconds={}",
                    account.accountId(), expectedSeconds, metadata.ephemeralDurationSeconds());
        } catch (ProtocolException ex) {
            log.warn("限时消息设置回读失败 accountId={} expectedSeconds={} code={}",
                    account.accountId(), expectedSeconds, ex.errorCode());
        }
        throw new BusinessException(
                ErrorCode.GROUP_PROTOCOL_TIMEOUT,
                "限时消息设置结果待确认，请刷新");
    }

    /**
     * 在写入前验证需要能力声明的群设置。
     *
     * <p>目前只有“通过链接邀请”没有稳定协议写能力，因此必须先读取 metadata capability。
     * 不支持时立即失败，禁止误用添加成员或入群审批设置。</p>
     *
     * @param account  执行账号
     * @param groupJid WhatsApp 群 JID
     * @param key      权限设置 key
     * @throws BusinessException 当 capability 不支持或读取失败时抛出
     */
    private void ensureSupportedSetting(
            GroupExecutionAccount account,
            String groupJid,
            GroupPermissionKey key) {
        if (key != GroupPermissionKey.INVITE_VIA_LINK) {
            return;
        }
        GroupMetadataResult metadata;
        try {
            metadata = protocolPorts.metadata().getMetadata(
                    account.protocolRef(), groupJid);
        } catch (ProtocolException ex) {
            throw groupBusinessException(ex);
        }
        if (!metadata.inviteViaLinkSupported()) {
            log.info("群权限能力不支持 accountId={} key={}", account.accountId(), key);
            throw new BusinessException(
                    ErrorCode.GROUP_CAPABILITY_UNSUPPORTED,
                    firstText(
                            metadata.inviteViaLinkUnsupportedReason(),
                            ErrorCode.GROUP_CAPABILITY_UNSUPPORTED.defaultMessage()));
        }
    }

    /**
     * 把固定权限枚举分派到对应的独立协议端口方法。
     *
     * @param account  执行账号
     * @param groupJid WhatsApp 群 JID
     * @param dto      权限 key 与期望状态
     */
    private void applySetting(
            GroupExecutionAccount account,
            String groupJid,
            GroupSettingCommandDTO dto) {
        switch (dto.key()) {
            case EDIT_GROUP_SETTINGS -> protocolPorts.settings().setEditGroupSettingsAllowed(
                    account.protocolRef(), groupJid, dto.enabled());
            case SEND_MESSAGES -> protocolPorts.settings().setSendMessagesAllowed(
                    account.protocolRef(), groupJid, dto.enabled());
            case ADD_MEMBERS -> protocolPorts.settings().setAddMembersAllowed(
                    account.protocolRef(), groupJid, dto.enabled());
            case INVITE_VIA_LINK -> protocolPorts.settings().setInviteViaLinkAllowed(
                    account.protocolRef(), groupJid, dto.enabled());
            case ADMIN_APPROVE_NEW_MEMBERS -> protocolPorts.settings().setJoinApprovalEnabled(
                    account.protocolRef(), groupJid, dto.enabled());
        }
    }

    /**
     * 使用原执行账号回读并确认一项群权限。
     *
     * @param account  原执行账号
     * @param groupJid WhatsApp 群 JID
     * @param key      权限 key
     * @param expected 期望布尔状态
     * @throws BusinessException 当回读失败或实际状态不一致时抛出
     */
    private void confirmSetting(
            GroupExecutionAccount account,
            String groupJid,
            GroupPermissionKey key,
            boolean expected) {
        try {
            GroupMetadataResult metadata = protocolPorts.metadata().getMetadata(
                    account.protocolRef(), groupJid);
            requireConfirmedPermission(metadata, key, expected);
        } catch (BusinessException ex) {
            log.warn("群权限设置回读状态不一致 accountId={} key={} expected={}",
                    account.accountId(), key, expected);
            throw ex;
        } catch (ProtocolException ex) {
            log.warn("群权限设置回读失败 accountId={} key={} expected={} code={}",
                    account.accountId(), key, expected, ex.errorCode());
            throw new BusinessException(
                    ErrorCode.GROUP_PROTOCOL_TIMEOUT,
                    "群设置结果待确认，请刷新");
        }
    }

    /**
     * 从 metadata 中取出指定权限并与期望值严格比较。
     *
     * <p>restrict 和 announce 是协议层反向语义，需要先取反；null 表示协议未给出确定状态，
     * 不允许当作 false 或成功处理。</p>
     *
     * @param metadata 群元数据
     * @param key      权限 key
     * @param expected 期望状态
     * @throws BusinessException 当实际值为空或与期望不一致时抛出
     */
    private static void requireConfirmedPermission(
            GroupMetadataResult metadata,
            GroupPermissionKey key,
            boolean expected) {
        Boolean actual = switch (key) {
            case EDIT_GROUP_SETTINGS -> invert(metadata.restrict());
            case SEND_MESSAGES -> invert(metadata.announce());
            case ADD_MEMBERS -> metadata.memberAddMode();
            case INVITE_VIA_LINK -> metadata.inviteViaLink();
            case ADMIN_APPROVE_NEW_MEMBERS -> metadata.joinApprovalMode();
        };
        if (!Boolean.valueOf(expected).equals(actual)) {
            throw new BusinessException(
                    ErrorCode.GROUP_PROTOCOL_TIMEOUT,
                    "设置请求已返回，但重新读取的 WhatsApp 状态不一致");
        }
    }

    /**
     * 将协议群操作异常转换为稳定业务错误码。
     *
     * @param ex 协议异常
     * @return 面向 Controller 的业务异常
     */
    private static BusinessException groupBusinessException(ProtocolException ex) {
        return switch (ex.errorCode()) {
            case GROUP_PERMISSION_DENIED -> new BusinessException(
                    ErrorCode.GROUP_PERMISSION_DENIED);
            case GROUP_CAPABILITY_UNSUPPORTED -> new BusinessException(
                    ErrorCode.GROUP_CAPABILITY_UNSUPPORTED);
            case TIMEOUT -> new BusinessException(ErrorCode.GROUP_PROTOCOL_TIMEOUT);
            default -> new BusinessException(ErrorCode.VALIDATION, "群设置修改失败");
        };
    }

    /**
     * 校验头像文件类型和大小，避免非图片或超大内容进入 base64 编码。
     *
     * @param file 上传文件
     * @throws BusinessException 当文件为空、不是图片或超过 5 MiB 时抛出
     */
    private static void validateAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择群头像");
        }
        String contentType = file.getContentType();
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith(IMAGE_MIME_PREFIX)) {
            throw new BusinessException(ErrorCode.VALIDATION, "只能上传图片文件");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION, "群头像不能超过 5 MiB");
        }
    }

    /**
     * 读取上传文件字节。
     *
     * @param file 已通过类型和大小校验的头像文件
     * @return 文件原始字节
     * @throws BusinessException 当底层文件读取失败时抛出
     */
    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "群头像读取失败");
        }
    }

    /**
     * 将群名称或头像协议异常转换为资料修改业务错误。
     *
     * @param ex 协议异常
     * @return 权限不足、超时待确认或通用资料修改失败
     */
    private static BusinessException profileMutationFailure(ProtocolException ex) {
        if (ex.errorCode() == ProtocolErrorCode.TIMEOUT) {
            return new BusinessException(
                    ErrorCode.GROUP_PROTOCOL_TIMEOUT,
                    "协议调用超时，操作结果待确认，请刷新");
        }
        if (ex.errorCode() == ProtocolErrorCode.GROUP_PERMISSION_DENIED) {
            return new BusinessException(ErrorCode.GROUP_PERMISSION_DENIED);
        }
        return new BusinessException(ErrorCode.VALIDATION, "群资料修改失败");
    }

    /**
     * 构造只包含本地资料的降级群详情。
     *
     * @param target    本地群目标
     * @param groupName 本地可展示群名称
     * @param avatarUrl 本地头像 URL 镜像
     * @param reason    实时数据不可用原因
     * @return 实时权限和成员均标记不可用的详情
     */
    private static GroupDetailVO unavailable(
            GroupTarget target,
            String groupName,
            String avatarUrl,
            String reason,
            GroupMetadataSyncTask task) {
        return new GroupDetailVO(
                target.link().getId(),
                target.groupJid(),
                groupName,
                target.link().getRemark(),
                avatarUrl,
                false,
                reason,
                null,
                new GroupDetailVO.Permissions(null, null, null, null, null),
                new GroupDetailVO.Capabilities(new GroupDetailVO.Capability(false, reason)),
                false,
                reason,
                List.of(),
                syncStatus(task),
                task == null ? null : task.getLastSuccessAt(),
                task == null ? null : task.getLastErrorMessage());
    }

    private static String syncStatus(GroupMetadataSyncTask task) {
        return task == null || task.getStatus() == null
                ? null
                : GroupMetadataSyncStatus.fromCode(task.getStatus()).name();
    }

    /**
     * 把协议层反向权限字段转换为页面正向语义，同时保留未知值。
     *
     * @param value 协议层反向布尔值
     * @return null 保持 null，其余取反
     */
    private static Boolean invert(Boolean value) {
        return value == null ? null : !value;
    }

    /**
     * 从两个候选文本中选择第一个非空值并去除首尾空白。
     *
     * @param first  优先值
     * @param second 备用值
     * @return 首个有效文本；两者均为空时返回 null
     */
    private static String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    /**
     * 把协议成员模型转换为群详情成员 VO。
     *
     * @param participant 协议层稳定成员结果
     * @return 前端成员 VO
     */
    private static GroupLinkMemberVO memberVO(GroupParticipantResult participant) {
        return new GroupLinkMemberVO(
                participant.jid(),
                participant.phone(),
                participant.admin(),
                participant.owner(),
                participant.role());
    }

    private static GroupLinkMemberVO memberVO(WhatsappGroupMemberSnapshot participant) {
        return new GroupLinkMemberVO(
                participant.getParticipantJid(),
                participant.getPhone(),
                participant.getIsAdmin(),
                participant.getIsOwner(),
                participant.getRole());
    }

    /**
     * 群详情编排使用的本地目标快照。
     *
     * @param link     活跃群链接
     * @param preview  可选群预览镜像
     * @param groupJid 已归一化群 JID，尚未解析时为空
     */
    private record GroupTarget(GroupLink link, GroupLinkPreview preview, String groupJid) {
    }
}
