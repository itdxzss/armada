package com.armada.task.service.impl;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.service.PullTaskStandardCreateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 普通群链接任务提交冻结实现。
 *
 * <p>单事务内完成 {@code DRAFT -> WAIT_START} 的全部落库：写冻结配置、回填群入口 ID、
 * 把执行行推进为待启动、推进任务状态。占用冲突整单回滚，重复提交幂等返回既有任务
 * （见类文档与各私有方法注释）。</p>
 */
@Service
public class PullTaskStandardCreateServiceImpl implements PullTaskStandardCreateService {

    private static final Logger log = LoggerFactory.getLogger(PullTaskStandardCreateServiceImpl.class);

    /** 启动时才按管理分组可用账号数冻结 N，创建时先写 0。 */
    private static final int REQUIRED_MANAGER_COUNT_PENDING = 0;

    /** 任务名最大长度。 */
    private static final int TASK_NAME_MAX_LENGTH = 128;

    /** 备注最大长度。 */
    private static final int REMARK_MAX_LENGTH = 512;

    /** 创建后是否自动启动：否。 */
    private static final int AUTO_START_NO = 0;

    /** 创建后是否自动启动：是。 */
    private static final int AUTO_START_YES = 1;

    /** 料子内管理员设置时点合法取值：入群后立即。 */
    private static final int ADMIN_TIMING_IMMEDIATE = 1;

    /** 料子内管理员设置时点合法取值：本群料子全部终态后。 */
    private static final int ADMIN_TIMING_AFTER_GROUP_DONE = 2;

    /** 单次拉人料子人数下限的最小允许值。 */
    private static final int PULL_COUNT_MIN_FLOOR = 1;

    /** 任务状态：草稿，尚未提交。 */
    private static final String STATUS_DRAFT = "DRAFT";

    /** 任务状态：已提交冻结，等待启动。 */
    private static final String STATUS_WAIT_START = "WAIT_START";

    private final PullTaskMapper pullTaskMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final GroupLinkRegistryService groupLinkRegistryService;
    private final AccountGroupService accountGroupService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建提交冻结服务。
     *
     * @param pullTaskMapper           任务主表数据访问
     * @param executionMapper          执行行数据访问
     * @param settingMapper            冻结执行配置数据访问
     * @param groupLinkRegistryService 群入口登记服务
     * @param accountGroupService      账号分组服务
     */
    public PullTaskStandardCreateServiceImpl(PullTaskMapper pullTaskMapper,
                                             PullTaskGroupExecutionMapper executionMapper,
                                             PullTaskStandardSettingMapper settingMapper,
                                             GroupLinkRegistryService groupLinkRegistryService,
                                             AccountGroupService accountGroupService) {
        this.pullTaskMapper = pullTaskMapper;
        this.executionMapper = executionMapper;
        this.settingMapper = settingMapper;
        this.groupLinkRegistryService = groupLinkRegistryService;
        this.accountGroupService = accountGroupService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PullTaskStandardCreatedVO create(PullTaskStandardCreateDTO request, long userId) {
        validate(request);
        PullTask task = requireOwnTask(request.draftTaskId(), userId);
        if (STATUS_WAIT_START.equals(task.getStatus())) {
            // 幂等分支：已经提交过。必须在写 setting 之前短路返回，
            // 否则重复提交会撞 pull_task_standard_setting 的主键。
            log.info("普通群链接任务重复提交，返回既有任务 taskId={}", task.getId());
            return toCreatedVO(task);
        }
        if (!STATUS_DRAFT.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "当前任务状态为 " + task.getStatus() + "，不允许提交");
        }

        List<PullTaskGroupExecution> rows = executionMapper.selectByTaskId(task.getId());
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "至少需要一条群链接与 TXT 的匹配");
        }

        settingMapper.insert(toSetting(request, task.getId()));
        fillGroupLinkIds(rows);
        freezeRows(task.getId());
        return submit(task, request, rows);
    }

    /**
     * 逐项校验提交入参；数值区间与枚举取值拆成两个私有方法以控制单方法行数。
     *
     * @param request 提交入参
     * @throws BusinessException 任一字段不合法时
     */
    private void validate(PullTaskStandardCreateDTO request) {
        if (request.draftTaskId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "缺少草稿任务 ID");
        }
        if (request.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "缺少草稿任务版本号");
        }
        String taskName = request.taskName() == null ? "" : request.taskName().trim();
        if (taskName.isEmpty() || taskName.length() > TASK_NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "任务名称长度需在 1-" + TASK_NAME_MAX_LENGTH + " 字符之间");
        }
        if (request.remark() != null && request.remark().length() > REMARK_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION, "备注不超过 " + REMARK_MAX_LENGTH + " 字符");
        }
        validateNumericRanges(request);
        validateEnumsAndGroups(request);
    }

    /**
     * 校验数值区间：拉人人数区间、间隔秒数、拉手数、站台数、并发行数、风控冷却分钟。
     *
     * @param request 提交入参
     * @throws BusinessException 任一数值不在合法区间时
     */
    private void validateNumericRanges(PullTaskStandardCreateDTO request) {
        if (request.pullCountMin() == null || request.pullCountMax() == null
                || request.pullCountMin() < PULL_COUNT_MIN_FLOOR
                || request.pullCountMin() > request.pullCountMax()) {
            throw new BusinessException(ErrorCode.VALIDATION, "单次拉人料子人数区间不合法");
        }
        if (request.pullIntervalSeconds() == null || request.pullIntervalSeconds() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "拉人调用最小间隔秒数不合法");
        }
        if (request.pullerCountPerGroup() == null || request.pullerCountPerGroup() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "每条执行行的计划拉手数不合法");
        }
        if (request.stationCountPerCall() == null || request.stationCountPerCall() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "每次拉人调用叠加的站台数不合法");
        }
        if (request.concurrentGroupCount() == null || request.concurrentGroupCount() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "最大同时运行执行行数不合法");
        }
        if (request.pullerRiskMinutes() == null || request.pullerRiskMinutes() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "拉手风控冷却分钟不合法");
        }
    }

    /**
     * 校验枚举取值，并确认三个账号分组 ID 都已填写。
     *
     * <p>分组是否真实存在、是否属于当前租户，交给 {@link #toSetting} 里的
     * {@code accountGroupService.requireExisting} 校验——那一步已经需要分组名称快照，
     * 这里重复查询没有意义。</p>
     *
     * @param request 提交入参
     * @throws BusinessException 枚举取值非法或分组 ID 缺失时
     */
    private void validateEnumsAndGroups(PullTaskStandardCreateDTO request) {
        if (request.autoStart() == null
                || (request.autoStart() != AUTO_START_NO && request.autoStart() != AUTO_START_YES)) {
            throw new BusinessException(ErrorCode.VALIDATION, "自动启动取值只能是 0 或 1");
        }
        if (request.materialAdminTiming() == null
                || (request.materialAdminTiming() != ADMIN_TIMING_IMMEDIATE
                    && request.materialAdminTiming() != ADMIN_TIMING_AFTER_GROUP_DONE)) {
            throw new BusinessException(ErrorCode.VALIDATION, "料子内管理员设置时点取值不合法");
        }
        if (request.managerGroupId() == null || request.pullerGroupId() == null
                || request.stationGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "管理、拉手、站台账号分组均不能为空");
        }
    }

    /**
     * 按 ID 取任务并校验归属，不对状态做任何假设。
     *
     * <p><b>刻意不用 {@code selectLatestDraftByCreator}</b>：那条查询带
     * {@code status = 'DRAFT'} 过滤，提交成功后任务已变成 {@code WAIT_START}，
     * 用它做提交入口的查找会让重复提交在结构上永远查不到自己、幂等无法成立。
     * 这里改用 {@code selectLifecycle(taskId)} 按主键直查，任何状态都能读到，
     * 状态与幂等分支由调用方（{@link #create}）显式判断。</p>
     *
     * @param taskId 提交入参携带的任务 ID
     * @param userId 当前登录用户 ID
     * @return 归属当前用户的任务行，状态不限
     * @throws BusinessException 任务不存在或不属于当前用户时
     */
    private PullTask requireOwnTask(Long taskId, long userId) {
        PullTask task = taskId == null ? null : pullTaskMapper.selectLifecycle(taskId);
        if (task == null || task.getCreatedBy() == null || !task.getCreatedBy().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "草稿不存在或不属于当前用户");
        }
        return task;
    }

    /**
     * 幂等分支用：把已提交任务的当前行直接组装成创建结果。
     *
     * @param task 已处于 {@code WAIT_START} 及之后状态的任务行
     * @return 创建结果视图
     */
    private static PullTaskStandardCreatedVO toCreatedVO(PullTask task) {
        return new PullTaskStandardCreatedVO(task.getId(), task.getTaskName(), task.getStatus(),
                task.getGroupCount(), task.getExpectedPullCount());
    }

    /**
     * 组装待写入的冻结执行配置，三个分组名称在此刻拍快照。
     *
     * @param request 提交入参
     * @param taskId  草稿任务 ID
     * @return 待插入的冻结配置
     */
    private PullTaskStandardSetting toSetting(PullTaskStandardCreateDTO request, long taskId) {
        AccountGroup managerGroup = accountGroupService.requireExisting(request.managerGroupId());
        AccountGroup pullerGroup = accountGroupService.requireExisting(request.pullerGroupId());
        AccountGroup stationGroup = accountGroupService.requireExisting(request.stationGroupId());

        PullTaskStandardSetting setting = new PullTaskStandardSetting();
        setting.setTaskId(taskId);
        setting.setAutoStart(request.autoStart());
        setting.setMaterialAdminTiming(request.materialAdminTiming());
        setting.setPullCountMin(request.pullCountMin());
        setting.setPullCountMax(request.pullCountMax());
        setting.setPullIntervalSeconds(request.pullIntervalSeconds());
        setting.setPullerCountPerGroup(request.pullerCountPerGroup());
        setting.setStationCountPerCall(request.stationCountPerCall());
        setting.setConcurrentGroupCount(request.concurrentGroupCount());
        setting.setPullerRiskMinutes(request.pullerRiskMinutes());
        setting.setRequiredManagerCount(REQUIRED_MANAGER_COUNT_PENDING);
        setting.setManagerGroupId(request.managerGroupId());
        setting.setPullerGroupId(request.pullerGroupId());
        setting.setStationGroupId(request.stationGroupId());
        setting.setManagerGroupName(managerGroup.getName());
        setting.setPullerGroupName(pullerGroup.getName());
        setting.setStationGroupName(stationGroup.getName());
        long now = System.currentTimeMillis();
        setting.setCreatedAt(now);
        setting.setUpdatedAt(now);
        return setting;
    }

    /**
     * 把执行行的群链接登记进群组池，并回填各行的 {@code group_link_id}。
     *
     * @param rows 草稿的全部执行行
     */
    private void fillGroupLinkIds(List<PullTaskGroupExecution> rows) {
        long now = System.currentTimeMillis();
        List<String> links = rows.stream().map(PullTaskGroupExecution::getNormalizedLink).toList();
        Map<String, Long> groupLinkIds = groupLinkRegistryService.registerPullTaskTargets(links, now);
        for (PullTaskGroupExecution row : rows) {
            Long groupLinkId = groupLinkIds.get(row.getNormalizedLink());
            if (groupLinkId != null) {
                executionMapper.updateGroupLinkId(row.getId(), groupLinkId, now);
            }
        }
    }

    /**
     * 推进执行行为待启动；此刻生成列 link_occupancy_key 生效，跨任务占用开始。
     *
     * @param taskId 草稿任务 ID
     * @throws BusinessException 任一链接已被其他任务占用时，整个事务回滚
     */
    private void freezeRows(long taskId) {
        try {
            executionMapper.freezeDraftRows(taskId, System.currentTimeMillis());
        } catch (DuplicateKeyException e) {
            // 不做"跳过冲突行、其余继续"：PRD 要求落库计划与创建页所见完全一致，
            // 偷偷少落一行用户无法察觉。
            log.warn("提交冻结时群链接被其他任务占用 taskId={}", taskId, e);
            throw new BusinessException(ErrorCode.CONFLICT,
                    "有群链接已被其他任务占用，请移除冲突行后重试");
        }
    }

    /**
     * 推进任务状态为 {@code WAIT_START}。
     *
     * <p>调用前已确认任务状态为 {@code DRAFT}，因此这里返回 0 只可能是版本已过期——
     * 另一次并发提交抢先完成。此时必须让整个事务（含刚写入的 setting 与刚冻结的
     * 执行行）一起回滚，而不是静默当作成功，否则会落下一份状态未推进、
     * 但配置与占用已经生效的不一致数据。</p>
     *
     * @param task    校验通过的草稿任务行
     * @param request 提交入参
     * @param rows    草稿的全部执行行
     * @return 创建完成的任务行
     * @throws BusinessException 版本已被并发提交抢先推进时
     */
    private PullTaskStandardCreatedVO submit(PullTask task, PullTaskStandardCreateDTO request,
                                             List<PullTaskGroupExecution> rows) {
        PullTask update = new PullTask();
        update.setId(task.getId());
        update.setTaskName(request.taskName().trim());
        update.setRemark(request.remark());
        update.setConfigJson(toConfigJson(request));
        update.setGroupCount(rows.size());
        update.setExpectedPullCount(rows.stream()
                .mapToInt(PullTaskGroupExecution::getValidMemberCount).sum());
        if (pullTaskMapper.submitDraft(update, request.version(), System.currentTimeMillis()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被并发提交，请刷新后重试");
        }
        PullTask saved = pullTaskMapper.selectLifecycle(task.getId());
        return new PullTaskStandardCreatedVO(saved.getId(), saved.getTaskName(),
                saved.getStatus(), update.getGroupCount(), update.getExpectedPullCount());
    }

    /**
     * 把提交入参序列化为配置快照 JSON。
     *
     * @param request 提交入参
     * @return 配置快照 JSON
     * @throws BusinessException 序列化失败时
     */
    private String toConfigJson(PullTaskStandardCreateDTO request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务配置序列化失败");
        }
    }
}
