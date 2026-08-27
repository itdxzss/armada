package com.armada.marketing.grouppull.service.impl;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.dto.CreateGroupPullMarketingTaskDTO;
import com.armada.marketing.grouppull.model.dto.GroupPullMarketingGroupQuery;
import com.armada.marketing.grouppull.model.dto.GroupPullMarketingTaskQuery;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullBlockReason;
import com.armada.marketing.grouppull.model.enums.GroupPullMaterialStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullSpeakPermission;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingGroupVO;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingTaskDetailVO;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingTaskVO;
import com.armada.marketing.grouppull.service.GroupPullMarketingMaterialParser;
import com.armada.marketing.grouppull.service.GroupPullMaterialEntryDelayPolicy;
import com.armada.marketing.grouppull.service.GroupPullMarketingTaskService;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingGroupOccupancyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 拉群营销任务配置、查询和生命周期服务实现。
 *
 * <p>创建事务统一保存公共营销任务、拉群扩展配置和料子池；启动事务负责校验资源并原子抢占营销分组；
 * 暂停、恢复、释放和删除只处理各自允许的状态边界。</p>
 */
@Service
public class GroupPullMarketingTaskServiceImpl implements GroupPullMarketingTaskService {

    /** 安全日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(GroupPullMarketingTaskServiceImpl.class);

    /** 单个营销账号默认最大成功进群数。 */
    private static final int DEFAULT_MARKETING_ACCOUNT_GROUP_LIMIT = 10;

    /** 默认营销轮次间隔（秒）。 */
    private static final int DEFAULT_SEND_INTERVAL_SECONDS = 30;

    /** 建群账号与营销账号互加失败后的默认重试次数，不包含首次。 */
    private static final int DEFAULT_FRIEND_RETRY_LIMIT = 3;

    /** 每个群默认抽取的料子数量。 */
    private static final int DEFAULT_MATERIAL_PER_GROUP = 3;

    /** 单次批量插入料子的最大行数。 */
    private static final int MATERIAL_INSERT_BATCH_SIZE = 1_000;

    /** 拉群营销任务、执行和料子数据访问。 */
    private final GroupPullMarketingMapper mapper;

    /** 公共营销任务数据访问。 */
    private final MarketingTaskMapper marketingTaskMapper;

    /** 营销模板数据访问。 */
    private final MarketingTemplateMapper templateMapper;

    /** 账号分组数据访问。 */
    private final AccountGroupMapper accountGroupMapper;

    /** 账号在线状态及协议身份查询服务。 */
    private final AccountProtocolLookupService accountProtocolLookupService;

    /** TXT/CSV 料子文件解析器。 */
    private final GroupPullMarketingMaterialParser materialParser;

    /** 营销分组整组占用服务。 */
    private final MarketingGroupOccupancyService groupOccupancyService;

    /** 营销账号任务占用服务。 */
    private final MarketingAccountOccupancyService accountOccupancyService;

    /** 逐料随机间隔策略。 */
    private final GroupPullMaterialEntryDelayPolicy materialEntryDelayPolicy;

    /**
     * 创建拉群营销任务业务服务。
     *
     * @param mapper 拉群营销任务、执行和料子数据访问
     * @param marketingTaskMapper 公共营销任务数据访问
     * @param templateMapper 营销模板数据访问
     * @param accountGroupMapper 账号分组数据访问
     * @param accountProtocolLookupService 账号在线状态及协议身份查询服务
     * @param materialParser TXT/CSV 料子文件解析器
     * @param groupOccupancyService 营销分组整组占用服务
     * @param accountOccupancyService 营销账号任务占用服务
     * @param materialEntryDelayPolicy 逐料随机间隔策略
     */
    public GroupPullMarketingTaskServiceImpl(GroupPullMarketingMapper mapper,
                                             MarketingTaskMapper marketingTaskMapper,
                                             MarketingTemplateMapper templateMapper,
                                             AccountGroupMapper accountGroupMapper,
                                             AccountProtocolLookupService accountProtocolLookupService,
                                             GroupPullMarketingMaterialParser materialParser,
                                             MarketingGroupOccupancyService groupOccupancyService,
                                             MarketingAccountOccupancyService accountOccupancyService,
                                             GroupPullMaterialEntryDelayPolicy materialEntryDelayPolicy) {
        this.mapper = mapper;
        this.marketingTaskMapper = marketingTaskMapper;
        this.templateMapper = templateMapper;
        this.accountGroupMapper = accountGroupMapper;
        this.accountProtocolLookupService = accountProtocolLookupService;
        this.materialParser = materialParser;
        this.groupOccupancyService = groupOccupancyService;
        this.accountOccupancyService = accountOccupancyService;
        this.materialEntryDelayPolicy = materialEntryDelayPolicy;
    }

    /**
     * 保存一条待启动拉群营销任务及其唯一料子文件。
     *
     * <p>配置、分组、在线账号、模板和文件解析任一失败时整个事务回滚；保存阶段不锁定营销分组，
     * 也不创建建群执行记录。</p>
     *
     * @param request 拉群营销任务配置
     * @param materialFile 本任务唯一 TXT 或 CSV 料子文件
     * @return 创建后的任务配置及汇总详情
     * @throws BusinessException 当配置、账号分组、模板或料子文件不符合要求时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupPullMarketingTaskDetailVO create(CreateGroupPullMarketingTaskDTO request,
                                                  MultipartFile materialFile) {
        long now = System.currentTimeMillis();
        validateRequest(request, now);
        DataScope scope = DataScopeAccess.requireCurrent();
        List<GroupPullMarketingMaterialParser.ParsedMaterial> parsedMaterials = materialParser.parse(materialFile);
        AccountGroup builderGroup = requireGroup(request.builderGroupId(), "建群账号分组不存在");
        AccountGroup marketingGroup = requireGroup(request.marketingGroupId(), "营销分组不存在");
        AccountGroup successGroup = requireOptionalGroup(
                request.successGroupId(), "建群成功转入分组不存在");
        AccountGroup failureGroup = requireOptionalGroup(
                request.failureGroupId(), "建群失败转入分组不存在");
        requireSameOwner(builderGroup, marketingGroup, successGroup, failureGroup);
        requireOnlineAccount(request.builderGroupId(), "建群账号分组没有正常在线账号");
        requireOnlineAccount(request.marketingGroupId(), "营销分组没有正常在线账号");
        MarketingTemplate template = templateMapper.selectByIdForUpdate(
                request.marketingTemplateId(), DataScopeAccess.requireCurrent());
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板不存在: " + request.marketingTemplateId());
        }
        DataScopeAccess.requireSameOwner(
                Arrays.asList(marketingGroup.getOwnerUserId(), template.getOwnerUserId()),
                "拉群营销任务分组与模板");
        DataScopeAccess.requireOwnedByActorForCreate(
                scope,
                Arrays.asList(marketingGroup.getOwnerUserId(), template.getOwnerUserId()),
                "拉群营销任务");

        MarketingTask marketingTask = buildMarketingTask(request, marketingGroup, template, now);
        marketingTask.setOwnerUserId(scope.ownerUserIdForCreate());
        marketingTaskMapper.insertTask(marketingTask);
        mapper.insertTask(buildExtension(request, marketingTask.getId(), now));
        insertMaterials(marketingTask.getId(), parsedMaterials, now);

        GroupPullMarketingTaskDetailVO detail =
                mapper.selectTaskDetailForScope(marketingTask.getId(), scope);
        if (detail == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群营销任务不存在: " + marketingTask.getId());
        }
        log.info("拉群营销任务已保存 taskId={} builderGroupId={} marketingGroupId={} materials={} status={}",
                marketingTask.getId(), builderGroup.getId(), marketingGroup.getId(), parsedMaterials.size(),
                marketingTask.getStatus());
        return detail;
    }

    /**
     * 按查询条件分页读取拉群营销一级任务列表。
     *
     * @param query 查询条件和分页参数；为空时使用统一默认分页
     * @return 当前页任务汇总及总数
     */
    @Override
    public PageResult<GroupPullMarketingTaskVO> list(GroupPullMarketingTaskQuery query) {
        GroupPullMarketingTaskQuery normalized = query == null ? new GroupPullMarketingTaskQuery() : query;
        normalized.applyDataScope(DataScopeAccess.requireCurrent());
        long total = mapper.countTasks(normalized);
        List<GroupPullMarketingTaskVO> rows = total == 0 ? List.of() : mapper.selectTasks(normalized);
        return PageResult.of(rows, normalized.getPage(), normalized.getPageSize(), total);
    }

    /**
     * 查询单条拉群营销任务配置及聚合统计。
     *
     * @param id 统一营销任务 ID
     * @return 任务配置及汇总详情
     * @throws BusinessException 当任务不存在时抛出
     */
    @Override
    public GroupPullMarketingTaskDetailVO detail(Long id) {
        GroupPullMarketingTaskDetailVO detail =
                mapper.selectTaskDetailForScope(id, DataScopeAccess.requireCurrent());
        if (detail == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群营销任务不存在: " + id);
        }
        return detail;
    }

    /**
     * 分页查询任务正式进入建群流程后的群组明细。
     *
     * <p>只返回已经冻结群名的正式建群执行，创建群组失败但已经进入正式流程的记录
     * 不会被过滤。群人数、营销发送状态等字段保持数据库真实空值。</p>
     *
     * @param taskId 统一营销任务 ID
     * @param query 分页参数；为空时使用统一默认分页
     * @return 按执行 ID 升序排列的群组明细及总数
     * @throws BusinessException 当拉群营销任务不存在时抛出
     */
    @Override
    public PageResult<GroupPullMarketingGroupVO> groups(
            Long taskId,
            GroupPullMarketingGroupQuery query) {
        requireAccessibleTask(taskId);
        requireExtension(taskId);
        GroupPullMarketingGroupQuery normalized = query == null
                ? new GroupPullMarketingGroupQuery()
                : query;
        long total = mapper.countTaskGroups(taskId);
        List<GroupPullMarketingGroupVO> rows = total == 0
                ? List.of()
                : mapper.selectTaskGroups(taskId, normalized);
        return PageResult.of(rows, normalized.getPage(), normalized.getPageSize(), total);
    }

    /**
     * 启动待启动任务并在同一事务内原子锁定整个营销分组。
     *
     * <p>启动前重新校验结束时间、建群账号、营销账号和上线前遗留账号占用；任一条件不满足时事务回滚，
     * 已抢到的营销分组锁也随事务撤销。</p>
     *
     * @param id 统一营销任务 ID
     * @return 启动后的任务详情
     * @throws BusinessException 当任务状态、账号资源或营销分组占用不允许启动时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupPullMarketingTaskDetailVO start(Long id) {
        long now = System.currentTimeMillis();
        MarketingTask task = requireTaskForUpdate(id);
        DataScopeAccess.requireAssignedOwner(task.getOwnerUserId(), "拉群营销任务");
        if (!Integer.valueOf(MarketingTaskStatus.PENDING.code()).equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有待启动任务可以启动");
        }
        if (task.getTaskEndAt() == null || task.getTaskEndAt() <= now) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已到结束时间，不能启动");
        }
        GroupPullMarketingTask extension = requireExtension(id);
        lockTaskAccountGroups(task.getAccountGroupId(), extension.getBuilderGroupId());
        if (!groupOccupancyService.tryLock(
                task.getAccountGroupId(), MarketingBusinessType.GROUP_PULL, id, now)) {
            throw new BusinessException(ErrorCode.CONFLICT, "营销分组正在被其他任务占用");
        }
        requireOnlineAccountForStart(task.getAccountGroupId(), "营销分组没有正常在线账号");
        if (accountOccupancyService.hasOtherActiveOccupanciesInGroup(task.getAccountGroupId(), id)) {
            throw new BusinessException(ErrorCode.CONFLICT, "营销分组内存在被其他任务占用的账号");
        }
        requireOnlineAccountForStart(extension.getBuilderGroupId(), "建群账号分组没有正常在线账号");
        int marketingAccountTotalCount = Math.toIntExact(
                accountGroupMapper.countAccountsByGroupId(task.getAccountGroupId()));
        if (mapper.markResourcesLocked(id, marketingAccountTotalCount, now) != 1
                || mapper.startTask(id, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务状态已变化，请刷新后重试");
        }
        log.info("拉群营销任务已启动 taskId={} marketingGroupId={} marketingAccounts={}",
                id, task.getAccountGroupId(), marketingAccountTotalCount);
        return requireDetail(id);
    }

    /**
     * 按分组 ID 升序短暂锁定启动涉及的建群分组和营销分组。
     *
     * <p>该行锁只存在于启动事务内，用于和人工迁移串行，不表示建群账号分组被持久化占用。</p>
     *
     * @param marketingGroupId 营销账号分组 ID
     * @param builderGroupId 建群账号分组 ID
     */
    private void lockTaskAccountGroups(Long marketingGroupId, Long builderGroupId) {
        Set<Long> groupIds = new TreeSet<>(List.of(marketingGroupId, builderGroupId));
        List<AccountGroup> groups = accountGroupMapper.selectByIdsForUpdate(List.copyOf(groupIds));
        if (groups.size() != groupIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "建群账号分组或营销分组不存在");
        }
        DataScope scope = DataScopeAccess.requireCurrent();
        groups.forEach(group -> DataScopeAccess.requireCanAccess(scope, group.getOwnerUserId(), "分组"));
        requireSameOwner(groups.toArray(AccountGroup[]::new));
    }

    /**
     * 暂停执行中的任务并继续保留营销分组及账号占用。
     *
     * @param id 统一营销任务 ID
     * @return 暂停后的任务详情
     * @throws BusinessException 当任务不是执行中状态时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupPullMarketingTaskDetailVO pause(Long id) {
        requireTaskForUpdate(id);
        if (mapper.pauseTask(id, System.currentTimeMillis()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有执行中的任务可以暂停");
        }
        return requireDetail(id);
    }

    /**
     * 恢复资源锁仍归属于本任务的已暂停任务。
     *
     * <p>恢复后基于当前账号和料子事实重新计算阻塞原因，不沿用暂停前的旧判断。</p>
     *
     * @param id 统一营销任务 ID
     * @return 恢复后的任务详情
     * @throws BusinessException 当任务未暂停或营销分组锁已经失效时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupPullMarketingTaskDetailVO resume(Long id) {
        long now = System.currentTimeMillis();
        MarketingTask task = requireTaskForUpdate(id);
        DataScopeAccess.requireAssignedOwner(task.getOwnerUserId(), "拉群营销任务");
        GroupPullMarketingTask extension = requireExtension(id);
        if (!Integer.valueOf(GroupPullResourceStatus.LOCKED.code()).equals(extension.getResourceStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务资源未锁定，不能恢复");
        }
        AccountGroup marketingGroup = accountGroupMapper.selectById(task.getAccountGroupId());
        if (marketingGroup == null
                || !Integer.valueOf(MarketingBusinessType.GROUP_PULL.code())
                        .equals(marketingGroup.getMarketingOccupancyType())
                || !id.equals(marketingGroup.getMarketingOccupancyTaskId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "营销分组锁已失效，不能恢复");
        }
        if (mapper.resumeTask(id, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有已暂停任务可以恢复");
        }
        GroupPullMaterialEntryDelayPolicy.DelayWindow delayWindow =
                materialEntryDelayPolicy.delayWindow(
                        extension.getMaterialEntryIntervalSeconds());
        mapper.rescheduleMaterialExecutionsOnResume(
                id,
                now,
                delayWindow.minDelayMillis(),
                delayWindow.maxDelayMillis());
        mapper.updateBlockReason(id, currentBlockReason(task, extension), now);
        return requireDetail(id);
    }

    /**
     * 人工结束执行中或已暂停任务并将资源状态切换为释放中。
     *
     * <p>本方法只停止继续分配并发起释放，已经进入关键阶段的执行及已提交消息由后台释放流程安全收口。</p>
     *
     * @param id 统一营销任务 ID
     * @return 发起安全释放后的任务详情
     * @throws BusinessException 当任务状态不允许释放时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupPullMarketingTaskDetailVO release(Long id) {
        long now = System.currentTimeMillis();
        requireTaskForUpdate(id);
        if (mapper.requestRelease(id, now) != 1 || mapper.markResourceReleasing(id, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有执行中或已暂停任务可以释放账号");
        }
        log.info("拉群营销任务进入安全释放 taskId={}", id);
        return requireDetail(id);
    }

    /**
     * 删除从未启动、未持有资源的拉群营销任务。
     *
     * <p>先删除扩展配置和未使用料子，再软删除公共营销任务；任一步失败时整个事务回滚。</p>
     *
     * @param id 统一营销任务 ID
     * @throws BusinessException 当任务不存在、已经启动或状态并发变化时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MarketingTask task = requireTaskForUpdate(id);
        if (!Integer.valueOf(MarketingTaskStatus.PENDING.code()).equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有待启动任务可以删除");
        }
        long now = System.currentTimeMillis();
        mapper.deleteTaskMaterials(id);
        mapper.deleteTaskExtension(id);
        if (mapper.softDeletePendingTask(id, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务状态已变化，请刷新后重试");
        }
        log.info("待启动拉群营销任务已删除 taskId={}", id);
    }

    /**
     * 校验不依赖数据库的任务配置。
     *
     * @param request 拉群营销任务配置
     * @param now 当前时间（epoch 毫秒）
     * @throws BusinessException 当必填项、文本长度、结束时间或权限码不合法时抛出
     */
    private void validateRequest(CreateGroupPullMarketingTaskDTO request, long now) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务配置不能为空");
        }
        requireText(request.taskName(), "任务名称不能为空", 128);
        requireId(request.builderGroupId(), "建群账号分组不能为空");
        requireId(request.marketingGroupId(), "营销分组不能为空");
        requireId(request.marketingTemplateId(), "营销模板不能为空");
        if (request.taskEndAt() == null || request.taskEndAt() <= now) {
            throw new BusinessException(ErrorCode.VALIDATION, "结束时间必须晚于当前时间");
        }
        if (StringUtils.hasText(request.groupNamePrefix()) && request.groupNamePrefix().trim().length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION, "群名前缀不能超过100个字符");
        }
        if (StringUtils.hasText(request.remark()) && request.remark().trim().length() > 512) {
            throw new BusinessException(ErrorCode.VALIDATION, "备注不能超过512个字符");
        }
        int speakPermission = valueOrDefault(
                request.speakPermission(), GroupPullSpeakPermission.UNCHANGED.code());
        try {
            GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(
                    request.materialEntryIntervalSeconds());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, exception.getMessage());
        }
        try {
            GroupPullSpeakPermission.fromCode(speakPermission);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "群组发言权限配置无效");
        }
    }

    /**
     * 锁定读取拉群营销公共任务，串行化生命周期变更。
     *
     * @param id 统一营销任务 ID
     * @return 已加行锁的公共营销任务
     * @throws BusinessException 当任务不存在时抛出
     */
    private MarketingTask requireTaskForUpdate(Long id) {
        MarketingTask task = mapper.selectTaskForUpdateForScope(id, DataScopeAccess.requireCurrent());
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群营销任务不存在: " + id);
        }
        requireGroup(task.getAccountGroupId(), "拉群营销任务不存在");
        return task;
    }

    /**
     * 读取拉群营销特有配置。
     *
     * @param id 统一营销任务 ID
     * @return 拉群营销扩展配置
     * @throws BusinessException 当扩展配置不存在时抛出
     */
    private GroupPullMarketingTask requireExtension(Long id) {
        GroupPullMarketingTask extension = mapper.selectTaskById(id);
        if (extension == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群营销任务配置不存在: " + id);
        }
        return extension;
    }

    /**
     * 读取生命周期操作后的最新任务详情。
     *
     * @param id 统一营销任务 ID
     * @return 任务配置及汇总详情
     * @throws BusinessException 当任务不存在时抛出
     */
    private GroupPullMarketingTaskDetailVO requireDetail(Long id) {
        GroupPullMarketingTaskDetailVO detail =
                mapper.selectTaskDetailForScope(id, DataScopeAccess.requireCurrent());
        if (detail == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群营销任务不存在: " + id);
        }
        return detail;
    }

    /** 子表查询前先校验公共营销任务根，避免通过 taskId 绕过 owner 边界。 */
    private MarketingTask requireAccessibleTask(Long id) {
        MarketingTask task = marketingTaskMapper.selectTaskByIdForScope(
                id, DataScopeAccess.requireCurrent());
        if (task == null
                || !Integer.valueOf(MarketingBusinessType.GROUP_PULL.code())
                        .equals(task.getBusinessType())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群营销任务不存在: " + id);
        }
        return task;
    }

    /**
     * 根据恢复时的实时资源事实计算任务阻塞原因。
     *
     * @param task 公共营销任务
     * @param extension 拉群营销扩展配置
     * @return 当前阻塞原因码；资源满足时返回“无”
     */
    private int currentBlockReason(MarketingTask task, GroupPullMarketingTask extension) {
        if (accountProtocolLookupService.findRandomOnlineNormalByGroupId(extension.getBuilderGroupId()).isEmpty()) {
            return GroupPullBlockReason.WAITING_BUILDER.code();
        }
        if (accountProtocolLookupService.findRandomOnlineNormalByGroupId(task.getAccountGroupId()).isEmpty()) {
            return GroupPullBlockReason.WAITING_MARKETER.code();
        }
        if (mapper.countAvailableMaterials(task.getId()) < extension.getMaterialPerGroup()) {
            return GroupPullBlockReason.WAITING_MATERIAL.code();
        }
        return GroupPullBlockReason.NONE.code();
    }

    /**
     * 校验启动阶段账号分组中至少存在一个正常在线协议账号。
     *
     * @param groupId 账号分组 ID
     * @param message 无可用账号时的业务提示
     * @throws BusinessException 当分组中没有正常在线协议账号时抛出冲突异常
     */
    private void requireOnlineAccountForStart(Long groupId, String message) {
        if (accountProtocolLookupService.findRandomOnlineNormalByGroupId(groupId).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }

    /**
     * 读取当前租户账号分组并校验存在性。
     *
     * @param id 账号分组 ID
     * @param message 分组不存在时的业务提示
     * @return 当前租户账号分组
     * @throws BusinessException 当分组不存在时抛出
     */
    private AccountGroup requireGroup(Long id, String message) {
        AccountGroup group = accountGroupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, message + ": " + id);
        }
        DataScopeAccess.requireCanAccess(
                DataScopeAccess.requireCurrent(), group.getOwnerUserId(), "分组");
        return group;
    }

    /**
     * 校验可选转入分组；未配置时不做数据库查询。
     *
     * @param id 可选账号分组 ID
     * @param message 分组不存在时的业务提示
     * @throws BusinessException 当已配置的分组不存在时抛出
     */
    private AccountGroup requireOptionalGroup(Long id, String message) {
        return id == null ? null : requireGroup(id, message);
    }

    /** 一个任务不能把不同用户的账号分组关联到一起。 */
    private void requireSameOwner(AccountGroup... groups) {
        Long ownerUserId = null;
        boolean initialized = false;
        for (AccountGroup group : groups) {
            if (group == null) {
                continue;
            }
            if (!initialized) {
                ownerUserId = group.getOwnerUserId();
                initialized = true;
            } else if (!Objects.equals(ownerUserId, group.getOwnerUserId())) {
                throw new BusinessException(ErrorCode.VALIDATION, "任务分组归属不一致");
            }
        }
    }

    /**
     * 校验创建阶段账号分组中至少存在一个正常在线协议账号。
     *
     * @param groupId 账号分组 ID
     * @param message 无可用账号时的业务提示
     * @throws BusinessException 当分组中没有正常在线协议账号时抛出校验异常
     */
    private void requireOnlineAccount(Long groupId, String message) {
        if (accountProtocolLookupService.findRandomOnlineNormalByGroupId(groupId).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
    }

    /**
     * 将创建配置和关联快照转换为公共营销任务实体。
     *
     * @param request 拉群营销任务配置
     * @param marketingGroup 营销账号来源分组
     * @param template 已校验存在的营销模板
     * @param now 创建时间（epoch 毫秒）
     * @return 待插入的公共营销任务实体
     */
    private MarketingTask buildMarketingTask(CreateGroupPullMarketingTaskDTO request,
                                              AccountGroup marketingGroup,
                                              MarketingTemplate template,
                                              long now) {
        MarketingTask task = new MarketingTask();
        task.setTaskName(request.taskName().trim());
        task.setBusinessType(MarketingBusinessType.GROUP_PULL.code());
        task.setAccountGroupId(marketingGroup.getId());
        task.setAccountGroupName(marketingGroup.getName());
        task.setMarketingTemplateId(template.getId());
        task.setMarketingTemplateName(template.getTemplateName());
        task.setStatus(MarketingTaskStatus.PENDING.code());
        task.setSelectedAccountCount(0);
        task.setTargetGroupCount(0);
        task.setTargetPairCount(0);
        task.setSentMessageCount(0);
        task.setFailedMessageCount(0);
        task.setSendPerRound(1);
        task.setAccountGroupSendIntervalMs(0);
        task.setSendIntervalSeconds(valueOrDefault(
                request.sendIntervalSeconds(), DEFAULT_SEND_INTERVAL_SECONDS));
        task.setOnlineCheckEnabled(true);
        task.setAbnormalGroupSkipped(true);
        task.setAutoRetryEnabled(true);
        task.setRetryLimit(1);
        task.setCurrentRoundNo(0L);
        task.setRemark(trimToNull(request.remark()));
        task.setTaskEndAt(request.taskEndAt());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    /**
     * 将创建配置转换为拉群营销特有扩展实体。
     *
     * @param request 拉群营销任务配置
     * @param taskId 已创建的统一营销任务 ID
     * @param now 创建时间（epoch 毫秒）
     * @return 待插入的拉群营销扩展实体
     */
    private GroupPullMarketingTask buildExtension(CreateGroupPullMarketingTaskDTO request,
                                                   Long taskId,
                                                   long now) {
        GroupPullMarketingTask row = new GroupPullMarketingTask();
        row.setMarketingTaskId(taskId);
        row.setBuilderGroupId(request.builderGroupId());
        row.setSuccessGroupId(request.successGroupId());
        row.setFailureGroupId(request.failureGroupId());
        row.setMarketingAccountGroupLimit(valueOrDefault(
                request.marketingAccountGroupLimit(), DEFAULT_MARKETING_ACCOUNT_GROUP_LIMIT));
        row.setGroupNamePrefix(trimToNull(request.groupNamePrefix()));
        row.setFriendRetryLimit(valueOrDefault(request.friendRetryLimit(), DEFAULT_FRIEND_RETRY_LIMIT));
        row.setMaterialPerGroup(valueOrDefault(request.materialPerGroup(), DEFAULT_MATERIAL_PER_GROUP));
        row.setMaterialEntryIntervalSeconds(
                GroupPullMaterialEntryDelayPolicy.normalizeBaseSeconds(
                        request.materialEntryIntervalSeconds()));
        row.setSpeakPermission(valueOrDefault(
                request.speakPermission(), GroupPullSpeakPermission.UNCHANGED.code()));
        row.setBuilderExitEnabled(request.builderExitEnabled() == null || request.builderExitEnabled());
        row.setBlockReason(GroupPullBlockReason.NONE.code());
        row.setResourceStatus(GroupPullResourceStatus.UNLOCKED.code());
        row.setMarketingAccountTotalCount(null);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * 按文件稳定顺序构建料子实体并分批写入任务料子池。
     *
     * @param taskId 统一营销任务 ID
     * @param parsed 已清洗去重的有效料子
     * @param now 创建时间（epoch 毫秒）
     */
    private void insertMaterials(Long taskId,
                                 List<GroupPullMarketingMaterialParser.ParsedMaterial> parsed,
                                 long now) {
        List<GroupPullMarketingMaterial> rows = new ArrayList<>(parsed.size());
        for (GroupPullMarketingMaterialParser.ParsedMaterial item : parsed) {
            GroupPullMarketingMaterial row = new GroupPullMarketingMaterial();
            row.setTaskId(taskId);
            row.setLineNo(item.lineNo());
            row.setPhone(item.phone());
            row.setStatus(GroupPullMaterialStatus.AVAILABLE.code());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            rows.add(row);
        }
        for (int from = 0; from < rows.size(); from += MATERIAL_INSERT_BATCH_SIZE) {
            mapper.insertMaterials(rows.subList(from, Math.min(from + MATERIAL_INSERT_BATCH_SIZE, rows.size())));
        }
    }

    /**
     * 校验必填文本及最大长度。
     *
     * @param value 待校验文本
     * @param emptyMessage 文本为空时的业务提示
     * @param maxLength 最大字符数
     * @throws BusinessException 当文本为空或超过最大长度时抛出
     */
    private static void requireText(String value, String emptyMessage, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION, emptyMessage);
        }
        if (value.trim().length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务名称不能超过" + maxLength + "个字符");
        }
    }

    /**
     * 校验必填 ID。
     *
     * @param value 待校验 ID
     * @param message ID 缺失时的业务提示
     * @throws BusinessException 当 ID 为空时抛出
     */
    private static void requireId(Long value, String message) {
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
    }

    /**
     * 读取可选整数配置并应用系统默认值。
     *
     * @param value 用户配置值，可空
     * @param defaultValue 系统默认值
     * @return 用户配置值或系统默认值
     */
    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    /**
     * 清理可选文本。
     *
     * @param value 待清理文本
     * @return 去除首尾空白后的文本；无有效内容时返回 {@code null}
     */
    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
