package com.armada.marketing.service.impl;

import com.armada.account.service.AccountService;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.marketing.converter.MarketingTemplateConverter;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingSelectionDTO;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.enums.MarketingTargetScope;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.marketing.model.vo.MarketingAccountTreeVO;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.marketing.model.vo.MarketingTaskAccountGroupStatRow;
import com.armada.marketing.model.vo.MarketingTaskAccountTargetVO;
import com.armada.marketing.model.vo.MarketingTaskDetailVO;
import com.armada.marketing.model.vo.MarketingTaskGroupStatVO;
import com.armada.marketing.model.vo.MarketingTaskTargetVO;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import com.armada.marketing.model.vo.MarketingTreeAccountVO;
import com.armada.marketing.service.MarketingTaskService;
import com.armada.marketing.service.MarketingTemplateService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 普通群组营销任务应用服务。
 *
 * <p>创建事务负责持久化任务与目标并立即锁定全部所选账号；启动、暂停、继续和手动关闭只推进
 * 五态生命周期，真实轮次发送由营销调度器处理。</p>
 *
 * <p>跨域事实来源保持单一:营销模板仍读 {@code marketing_template};账号/群事实在建目标时
 * 从 {@code account}、{@code group_link}、{@code group_link_preview} 拼快照,不在任务表复制更多运行态。</p>
 */
@Service
public class MarketingTaskServiceImpl implements MarketingTaskService {

    private static final Logger log = LoggerFactory.getLogger(MarketingTaskServiceImpl.class);
    private static final int STATUS_PENDING = MarketingTaskStatus.PENDING.code();
    private static final int STATUS_SENDING = MarketingTaskStatus.SENDING.code();
    private static final int STATUS_PAUSED = MarketingTaskStatus.PAUSED.code();
    private static final int STATUS_COMPLETED = MarketingTaskStatus.COMPLETED.code();
    private static final int STATUS_CLOSED = MarketingTaskStatus.CLOSED.code();
    private static final long ACCOUNT_GROUP_SEND_LOOKBACK_MS = 72L * 60L * 60L * 1000L;
    private static final BigDecimal MIN_ACCOUNT_GROUP_SEND_INTERVAL_SECONDS = new BigDecimal("0.5");
    private static final BigDecimal MAX_ACCOUNT_GROUP_SEND_INTERVAL_SECONDS = new BigDecimal("3");
    private static final int DEFAULT_ACCOUNT_GROUP_SEND_INTERVAL_MS = 500;

    private final MarketingTaskMapper taskMapper;
    private final MarketingTemplateMapper templateMapper;
    private final MarketingTemplateService templateService;
    private final MarketingAccountTreeRealtimeService accountTreeRealtimeService;
    private final MarketingAccountOccupancyService occupancyService;
    private final AccountService accountService;

    /**
     * 注入营销任务 Mapper 与营销模板 Mapper。
     *
     * <p>任务 Mapper 负责本聚合读写；模板 Mapper 负责校验模板存在、读取模板名称快照，
     * 并为任务响应补充当前模板展示字段；模板正文不复制到任务表。</p>
     *
     * @param taskMapper      营销任务与目标明细数据访问
     * @param templateMapper  营销模板数据访问
     * @param templateService 营销模板业务服务
     * @param accountTreeRealtimeService 营销账号树实时查询服务
     * @param occupancyService 普通营销账号占用服务
     * @param accountService 账号域服务，用于批量读取当前登录态
     */
    public MarketingTaskServiceImpl(MarketingTaskMapper taskMapper,
                                    MarketingTemplateMapper templateMapper,
                                    MarketingTemplateService templateService,
                                    MarketingAccountTreeRealtimeService accountTreeRealtimeService,
                                    MarketingAccountOccupancyService occupancyService,
                                    AccountService accountService) {
        this.taskMapper = taskMapper;
        this.templateMapper = templateMapper;
        this.templateService = templateService;
        this.accountTreeRealtimeService = accountTreeRealtimeService;
        this.occupancyService = occupancyService;
        this.accountService = accountService;
    }

    /**
     * 分页查询营销任务列表。
     *
     * <p>按查询对象里的 ID、任务名称关键词、状态、最后发送时间范围过滤;SQL 层分页,
     * 返回列表行 VO。列表不加载 target 明细,避免任务列表查询被明细行数量放大。</p>
     *
     * @param query 分页与筛选条件
     * @return 当前页营销任务列表
     */
    @Override
    public PageResult<MarketingTaskVO> listTasks(MarketingTaskQuery query) {
        long total = taskMapper.countPage(query);
        // 与其它列表服务保持一致:total=0 时不再查 page rows,避免一次必然空结果的 SELECT。
        List<MarketingTaskVO> rows;
        if (total == 0) {
            rows = List.of();
        } else {
            List<MarketingTask> tasks = taskMapper.selectPage(query);
            Map<Long, MarketingTemplate> templatesById = loadTemplatesById(tasks);
            rows = tasks.stream()
                    .map(task -> toVO(task, templatesById.get(task.getMarketingTemplateId())))
                    .toList();
        }
        log.info("营销任务列表查询 total={} page={} pageSize={}", total, query.getPage(), query.getPageSize());
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /**
     * 新建营销任务并生成固定群组或账号动态目标明细。
     *
     * <p>本方法会校验基础入参、确认营销模板存在、把前端提交的账号维度选择拆成
     * `marketing_task_target` 执行目标:固定群组维度落账号+群组多行,账号动态维度只落账号一行。
     * 若启动模式是
     * `IMMEDIATE`,只把任务状态置为发送中并写 `started_at`,不调用协议层、不发送消息。</p>
     *
     * @param request 新建任务表单入参
     * @return 创建后的任务列表行视图
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTaskVO createTask(CreateMarketingTaskDTO request) {
        // 事务顺序固定:先校验共享事实,再生成目标快照,最后插主表和明细。
        // 任何一个目标不可用都整单失败,避免页面看到"半个任务"。
        long now = System.currentTimeMillis();
        validateRequest(request, now);
        MarketingTemplate template = requireTemplateForTaskCreation(request.marketingTemplateId());
        List<MarketingTaskTarget> targets = buildTargets(request, now);
        MarketingTask task = buildTask(request, template, targets, now);
        taskMapper.insertTask(task);
        // 主表自增 id 回填后才能写 target.marketing_task_id。
        for (MarketingTaskTarget target : targets) {
            target.setMarketingTaskId(task.getId());
        }
        taskMapper.insertTargets(targets);
        Map<Long, MarketingAccountOccupancyOwnerRow> lockedAccounts =
                occupancyService.lockTaskAccountsOrThrow(task, now);
        log.info("营销任务已创建 tenantId={} taskId={} targets={} lockedAccounts={} status={}",
                task.getTenantId(), task.getId(), targets.size(), lockedAccounts.size(), task.getStatus());
        return toVO(taskMapper.selectTaskById(task.getId()), template);
    }

    /**
     * 查询营销任务详情。
     *
     * <p>详情按账号、群组两级聚合：账号层批量补充当前实时登录态和当前任务成功发送总次数；
     * 群组层展示成功发送次数，并从同一条最后有效尝试归一群组状态、执行结果和失败原因。
     * 任务不存在时抛业务 404。</p>
     *
     * @param id 营销任务 ID
     * @return 营销任务详情,包含目标明细列表
     */
    @Override
    public MarketingTaskDetailVO getDetail(Long id) {
        // 详情用主表 + target 明细两次查询,不在列表 SQL 里提前聚合明细,保持列表轻量。
        MarketingTask task = requireTask(id);
        List<MarketingTaskTargetVO> targets = taskMapper.selectTargetsByTaskId(id)
                .stream().map(MarketingTaskServiceImpl::toTargetVO).toList();
        List<MarketingTaskAccountGroupStatRow> groupStats = taskMapper.selectAccountGroupStatsByTaskId(id);
        Set<Long> accountIds = targets.stream()
                .map(MarketingTaskTargetVO::accountId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Integer> loginStates = accountService.getLoginStatesByIds(List.copyOf(accountIds));
        List<MarketingTaskAccountTargetVO> accountTargets = toAccountTargets(targets, groupStats, loginStates);
        log.info("营销任务详情查询 id={} targets={} accounts={}", id, targets.size(), accountTargets.size());
        return toDetailVO(task, targets, accountTargets);
    }

    /**
     * 启动未启动的营销任务。
     *
     * <p>未到计划开始时间时只校验任务可启动，状态仍保持未启动，由调度器到点自动执行。
     * 本入口不改写计划时间，已完成或已关闭任务也不允许再次启动。</p>
     *
     * @param id 营销任务 ID
     * @return 启动后的任务主信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTaskVO startTask(Long id) {
        MarketingTask task = requireTask(id);
        if (!Integer.valueOf(STATUS_PENDING).equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION, "只有未启动的任务可以启动");
        }
        validateTaskTemplateAvailable(task);
        long now = System.currentTimeMillis();
        if (task.getTaskEndAt() != null && task.getTaskEndAt() <= now) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务计划已结束，已完成任务不可再次启动");
        }
        if (task.getTaskStartAt() != null && task.getTaskStartAt() > now) {
            log.info("营销任务等待计划开始 tenantId={} taskId={} taskStartAt={} taskEndAt={}",
                    task.getTenantId(), id, task.getTaskStartAt(), task.getTaskEndAt());
            return toVO(task);
        }

        int updated = taskMapper.startPendingTask(id, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务状态已变化,请刷新后重试");
        }
        log.info("营销任务手动启动 tenantId={} taskId={} taskStartAt={} taskEndAt={}",
                task.getTenantId(), id, task.getTaskStartAt(), task.getTaskEndAt());
        return toVO(requireTask(id));
    }

    /**
     * 暂停执行中的营销任务，停止生成后续轮次但保留全部账号锁。
     *
     * @param id 营销任务 ID
     * @return 暂停后的任务主信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTaskVO pauseTask(Long id) {
        MarketingTask task = requireTask(id);
        if (!Integer.valueOf(STATUS_SENDING).equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION, "只有执行中的任务可以暂停");
        }
        long now = System.currentTimeMillis();
        int updated = taskMapper.pauseSendingTask(id, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务状态已变化,请刷新后重试");
        }
        log.info("营销任务暂停 tenantId={} taskId={} accountsRetained=true", task.getTenantId(), id);
        return toVO(requireTask(id));
    }

    /**
     * 恢复已暂停的营销任务。
     *
     * @param id 营销任务 ID
     * @return 恢复后的任务主信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTaskVO resumeTask(Long id) {
        MarketingTask task = requireTask(id);
        if (!Integer.valueOf(STATUS_PAUSED).equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION, "只有已暂停的任务可以继续");
        }
        validateTaskTemplateAvailable(task);
        long now = System.currentTimeMillis();
        if (task.getTaskEndAt() != null && task.getTaskEndAt() <= now) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务计划已结束，已完成任务不可继续");
        }
        if (task.getTaskStartAt() != null && task.getTaskStartAt() > now) {
            throw new BusinessException(ErrorCode.VALIDATION, "未到任务计划开始时间，暂不可继续");
        }
        int updated = taskMapper.resumePausedTask(id, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务状态已变化,请刷新后重试");
        }
        log.info("营销任务继续 tenantId={} taskId={} taskEndAt={}", task.getTenantId(), id, task.getTaskEndAt());
        return toVO(requireTask(id));
    }

    /**
     * 手动关闭非终态任务并释放全部账号。
     *
     * <p>关闭只阻止后续轮次生成；已写入 Outbox 或消息队列的命令按原链路继续处理。</p>
     *
     * @param id 营销任务 ID
     * @return 已关闭任务主信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTaskVO closeTask(Long id) {
        MarketingTask task = requireTask(id);
        if (Integer.valueOf(STATUS_COMPLETED).equals(task.getStatus())
                || Integer.valueOf(STATUS_CLOSED).equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION, "已完成或已关闭的任务不可手动关闭");
        }
        long now = System.currentTimeMillis();
        int updated = taskMapper.closeActiveTask(id, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务状态已变化,请刷新后重试");
        }
        int released = occupancyService.releaseTaskAccounts(id);
        log.info("营销任务手动关闭 tenantId={} taskId={} releasedAccounts={}",
                task.getTenantId(), id, released);
        return toVO(requireTask(id));
    }

    /**
     * 批量软删已完成或已关闭的营销任务。
     *
     * <p>null/空列表直接返回 0。未启动、执行中和已暂停任务仍持有账号，必须先手动关闭，
     * SQL 再以终态集合守卫，避免软删除成为绕过账号释放规则的旁路。</p>
     *
     * @param ids 要删除的任务 ID 列表
     * @return 实际软删行数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        List<Long> normalizedIds = normalizeIds(ids);
        if (normalizedIds.isEmpty()) {
            return 0;
        }
        if (taskMapper.countActiveByIds(normalizedIds) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "未结束的任务不可删除，请先手动关闭任务");
        }
        int deleted = taskMapper.batchSoftDelete(normalizedIds, System.currentTimeMillis());
        log.info("营销任务批量软删 请求={} 实删={}", normalizedIds.size(), deleted);
        return deleted;
    }

    /**
     * 查询建营销任务用的账号树首屏。
     *
     * <p>只返回账号分组内登录态在线、无风控/禁言的账号,不在首屏调用协议层查群。
     * 群组由前端展开账号节点时懒加载。</p>
     *
     * @param groupId 账号分组 ID
     * @return 账号→可营销群树
     */
    @Override
    public MarketingAccountTreeVO accountTree(Long groupId) {
        return accountTreeRealtimeService.accountTree(groupId);
    }

    @Override
    public MarketingTreeAccountVO accountGroups(Long accountId) {
        return accountTreeRealtimeService.accountGroups(accountId);
    }

    /**
     * 通过营销任务更新其引用的共享营销模板。
     *
     * <p>一期需求中,任务详情里修改营销素材不是生成任务私有副本,而是覆盖该任务引用的
     * `marketing_template`。本方法只负责确认任务存在并定位模板 ID,具体模板名称查重、按钮规则、
     * 内容必填等校验全部委托 {@link MarketingTemplateService#update(Long, MarketingTemplateDTO)}。</p>
     *
     * @param id      营销任务 ID
     * @param request 新的营销模板配置
     * @return 更新后的营销模板视图
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTemplateVO updateMarketingTemplate(Long id, MarketingTemplateDTO request) {
        MarketingTask task = requireTask(id);
        MarketingTemplateVO updated = templateService.update(task.getMarketingTemplateId(), request);
        log.info("营销任务侧更新模板 taskId={} templateId={}", id, task.getMarketingTemplateId());
        return updated;
    }

    private void validateRequest(CreateMarketingTaskDTO request, long now) {
        // 只校验页面表单本身能确定的必填和数值约束;账号/群/模板是否真的可用在后续查库校验。
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销任务不能为空");
        }
        if (!StringUtils.hasText(request.taskName())) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务名称不能为空");
        }
        if (request.accountGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择账号分组");
        }
        if (request.marketingTemplateId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择营销模板");
        }
        if (positive(request.sendPerRound()) < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "单次发送数量必须为正整数");
        }
        if (!validAccountGroupSendInterval(request.accountGroupSendIntervalSeconds())) {
            throw new BusinessException(ErrorCode.VALIDATION, "单账号下群组发送间隔必须为0.5到3秒，最多一位小数");
        }
        if (positive(request.sendIntervalSeconds()) < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "发送间隔必须为正整数");
        }
        if (request.selections() == null || request.selections().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请至少选择一个发送账号");
        }
        validateLifecycleTimes(request, now);
    }

    private void validateLifecycleTimes(CreateMarketingTaskDTO request, long now) {
        Long accountGroupSendAt = request.accountGroupSendAt();
        if (accountGroupSendAt != null && accountGroupSendAt < now - ACCOUNT_GROUP_SEND_LOOKBACK_MS) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群组发送时间最多支持追溯72小时");
        }
        Long taskEndAt = request.taskEndAt();
        if (taskEndAt != null && taskEndAt <= now) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务结束时间必须晚于当前时间");
        }
        Long taskStartAt = request.taskStartAt();
        if (taskStartAt != null && taskEndAt != null && taskEndAt <= taskStartAt) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务结束时间必须晚于任务开始时间");
        }
    }

    private MarketingTemplate requireTemplateForTaskCreation(Long id) {
        // 模板是素材唯一事实源。行锁一直持有到创建事务提交，和模板删除形成明确的串行顺序。
        // 任务只保存模板 id/name 快照，不复制正文和按钮。
        MarketingTemplate template = templateMapper.selectByIdForUpdate(id);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板不存在: " + id);
        }
        return template;
    }

    private void validateTaskTemplateAvailable(MarketingTask task) {
        // selectById 只返回未软删除模板；必须在状态更新 SQL 前校验，保证拒绝启动时任务不变。
        if (templateMapper.selectById(task.getMarketingTemplateId()) != null) {
            return;
        }
        log.warn(
                "营销任务启动被拒绝:引用模板已删除 tenantId={} taskId={} templateId={}",
                task.getTenantId(),
                task.getId(),
                task.getMarketingTemplateId());
        throw new BusinessException(ErrorCode.VALIDATION, "营销模板已删除，任务不可启动");
    }

    private MarketingTask requireTask(Long id) {
        MarketingTask task = taskMapper.selectTaskById(id);
        if (task == null || (task.getBusinessType() != null
                && !Integer.valueOf(MarketingBusinessType.ORDINARY.code()).equals(task.getBusinessType()))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销任务不存在: " + id);
        }
        return task;
    }

    private List<MarketingTaskTarget> buildTargets(CreateMarketingTaskDTO request, long now) {
        // 同一任务可以混合两种维度:
        // GROUP_FIXED 保存 account x group 多行;ACCOUNT_DYNAMIC 只保存账号一行,发送前再解析当前新增群。
        // LinkedHashSet 既去重,又保留前端选择顺序,详情默认按插入顺序展示。
        Set<String> seenTargets = new LinkedHashSet<>();
        List<MarketingTaskTarget> targets = new ArrayList<>();
        for (MarketingSelectionDTO selection : request.selections()) {
            appendSelectionTargets(request.accountGroupId(), selection, seenTargets, targets, now);
        }
        if (targets.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请至少选择一个可执行账号或群组");
        }
        return targets;
    }

    private void appendSelectionTargets(Long accountGroupId, MarketingSelectionDTO selection, Set<String> seenTargets,
                                        List<MarketingTaskTarget> targets, long now) {
        // 一个 selection 代表一个发言账号。账号为空是前端状态不一致,直接拒绝。
        if (selection == null || selection.accountId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "发送账号不能为空");
        }
        MarketingTargetScope targetScope = selectionScope(selection);
        if (targetScope.isAccountDynamic()) {
            appendAccountDynamicTarget(accountGroupId, selection.accountId(), seenTargets, targets, now);
            return;
        }

        List<Long> groupLinkIds = normalizeGroupLinkIds(selection.groupLinkIds());
        if (groupLinkIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "固定群组维度请至少选择一个群组");
        }
        for (Long groupLinkId : groupLinkIds) {
            // 重复固定群目标静默跳过:这不是业务错误,只是前端重复提交或用户重复勾选的防御。
            if (!seenTargets.add(targetKey(targetScope, selection.accountId(), groupLinkId))) {
                continue;
            }
            targets.add(toFixedGroupTarget(requireCandidate(accountGroupId, selection.accountId(), groupLinkId), now));
        }
    }

    private void appendAccountDynamicTarget(Long accountGroupId, Long accountId, Set<String> seenTargets,
                                            List<MarketingTaskTarget> targets, long now) {
        // 账号动态维度不在创建任务时展开群,否则就无法覆盖账号导入云控后新进入的群。
        if (!seenTargets.add(targetKey(MarketingTargetScope.ACCOUNT_DYNAMIC, accountId, null))) {
            return;
        }
        targets.add(toAccountDynamicTarget(requireAccountCandidate(accountGroupId, accountId), now));
    }

    private MarketingTargetScope selectionScope(MarketingSelectionDTO selection) {
        if (StringUtils.hasText(selection.targetScope())) {
            return MarketingTargetScope.fromApiValue(selection.targetScope());
        }
        return normalizeGroupLinkIds(selection.groupLinkIds()).isEmpty()
                ? MarketingTargetScope.ACCOUNT_DYNAMIC
                : MarketingTargetScope.GROUP_FIXED;
    }

    private static String targetKey(MarketingTargetScope scope, Long accountId, Long groupLinkId) {
        return scope.apiValue() + ":" + accountId + ":" + (groupLinkId == null ? 0 : groupLinkId);
    }

    private static List<Long> normalizeGroupLinkIds(List<Long> groupLinkIds) {
        return groupLinkIds == null ? List.of() : groupLinkIds.stream()
                .filter(id -> id != null)
                .distinct()
                .toList();
    }

    private MarketingTargetCandidateRow requireCandidate(Long accountGroupId, Long accountId, Long groupLinkId) {
        // 目标候选必须同时满足:账号存在且属于本次选择的分组、登录态在线、群入口未软删且有 group_jid。
        // group_jid 是协议层发送寻址必需字段,没有它时不能等到发送阶段才失败。
        MarketingTargetCandidateRow row = taskMapper.selectTargetCandidate(
                accountGroupId,
                accountId,
                groupLinkId,
                MarketingAccountEligibility.selectableAccountStates());
        if (row == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号或群组不可用: account=" + accountId + ", group=" + groupLinkId);
        }
        if (!StringUtils.hasText(row.getGroupJid())) {
            throw new BusinessException(ErrorCode.VALIDATION, "目标群缺少群JID: " + groupLinkId);
        }
        return row;
    }

    private MarketingTargetCandidateRow requireAccountCandidate(Long accountGroupId, Long accountId) {
        // 账号动态目标只校验账号归属、在线、无风控/禁言。群是否可发由每轮发送前的动态群查询决定。
        MarketingTargetCandidateRow row = taskMapper.selectAccountTargetCandidate(
                accountGroupId,
                accountId,
                MarketingAccountEligibility.selectableAccountStates());
        if (row == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号不可用: account=" + accountId);
        }
        return row;
    }

    private MarketingTaskTarget toFixedGroupTarget(MarketingTargetCandidateRow row, long now) {
        // target 保存的是执行时需要稳定展示/寻址的快照。账号或群名后续变更不回写历史任务明细。
        MarketingTaskTarget target = new MarketingTaskTarget();
        fillTargetDefaults(target, row, now);
        target.setTargetScope(MarketingTargetScope.GROUP_FIXED.code());
        target.setGroupLinkId(row.getGroupLinkId());
        target.setGroupJid(row.getGroupJid());
        target.setGroupLinkUrl(row.getGroupLinkUrl());
        target.setGroupName(row.getGroupName());
        return target;
    }

    private MarketingTaskTarget toAccountDynamicTarget(MarketingTargetCandidateRow row, long now) {
        MarketingTaskTarget target = new MarketingTaskTarget();
        fillTargetDefaults(target, row, now);
        target.setTargetScope(MarketingTargetScope.ACCOUNT_DYNAMIC.code());
        return target;
    }

    private void fillTargetDefaults(MarketingTaskTarget target, MarketingTargetCandidateRow row, long now) {
        target.setAccountId(row.getAccountId());
        target.setAccountPhone(row.getAccountPhone());
        target.setStatus(MarketingTaskStatus.PENDING.code());
        target.setSentMessageCount(0);
        target.setFailedMessageCount(0);
        target.setRetryCount(0);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
    }

    private MarketingTask buildTask(CreateMarketingTaskDTO request, MarketingTemplate template,
                                    List<MarketingTaskTarget> targets, long now) {
        Long taskStartAt = normalizeTaskStartAt(request, now);
        Long taskEndAt = request.taskEndAt();
        Long accountGroupSendAt = normalizeAccountGroupSendAt(request, taskStartAt, now);
        MarketingTaskStatus status = initialStatus(request, taskStartAt, taskEndAt, now);
        MarketingTask task = new MarketingTask();
        task.setTaskName(request.taskName().trim());
        task.setBusinessType(MarketingBusinessType.ORDINARY.code());
        task.setAccountGroupId(request.accountGroupId());
        task.setAccountGroupName(snapshotName(request.accountGroupName(), "账号分组-" + request.accountGroupId()));
        task.setMarketingTemplateId(template.getId());
        task.setMarketingTemplateName(template.getTemplateName());
        task.setStatus(status.code());
        // 账号数和执行目标行数在创建时确定；累计成功群数只能由成功结果回调递增。
        task.setSelectedAccountCount(distinctAccountCount(targets));
        task.setTargetGroupCount(0);
        task.setTargetPairCount(targets.size());
        task.setSentMessageCount(0);
        task.setFailedMessageCount(0);
        task.setSendPerRound(positive(request.sendPerRound()));
        task.setAccountGroupSendIntervalMs(normalizeAccountGroupSendIntervalMs(
                request.accountGroupSendIntervalSeconds()));
        task.setSendIntervalSeconds(positive(request.sendIntervalSeconds()));
        task.setOnlineCheckEnabled(Boolean.TRUE.equals(request.onlineCheckEnabled()));
        task.setAbnormalGroupSkipped(Boolean.TRUE.equals(request.abnormalGroupSkipped()));
        task.setAutoRetryEnabled(Boolean.TRUE.equals(request.autoRetryEnabled()));
        // 一期需求只表达"失败后自动重试一次";后续若做可配置次数再扩 DTO。
        task.setRetryLimit(Boolean.TRUE.equals(request.autoRetryEnabled()) ? 1 : 0);
        task.setCurrentRoundNo(0L);
        task.setRemark(request.remark());
        task.setAccountGroupSendAt(accountGroupSendAt);
        task.setTaskStartAt(taskStartAt);
        task.setTaskEndAt(taskEndAt);
        task.setStartedAt(status == MarketingTaskStatus.SENDING ? now : null);
        task.setNextRoundAt(status == MarketingTaskStatus.SENDING ? now : null);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return task;
    }

    private static Long normalizeTaskStartAt(CreateMarketingTaskDTO request, long now) {
        if (request.taskStartAt() != null) {
            return request.taskStartAt();
        }
        return MarketingTaskStatus.fromStartMode(request.startMode()) == MarketingTaskStatus.SENDING ? now : null;
    }

    private static Long normalizeAccountGroupSendAt(CreateMarketingTaskDTO request, Long taskStartAt, long now) {
        if (request.accountGroupSendAt() != null) {
            return request.accountGroupSendAt();
        }
        long base = taskStartAt == null ? now : taskStartAt;
        return base - ACCOUNT_GROUP_SEND_LOOKBACK_MS;
    }

    private static MarketingTaskStatus initialStatus(CreateMarketingTaskDTO request,
                                                     Long taskStartAt,
                                                     Long taskEndAt,
                                                     long now) {
        if (taskStartAt != null && taskStartAt <= now && (taskEndAt == null || taskEndAt > now)) {
            return MarketingTaskStatus.SENDING;
        }
        if (taskStartAt != null) {
            return MarketingTaskStatus.PENDING;
        }
        return MarketingTaskStatus.fromStartMode(request.startMode());
    }

    private static List<Long> normalizeIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(id -> id != null).distinct().toList();
    }

    private static int positive(Integer value) {
        return value == null ? 0 : value;
    }

    private static boolean validAccountGroupSendInterval(BigDecimal value) {
        return value == null
                || (value.compareTo(MIN_ACCOUNT_GROUP_SEND_INTERVAL_SECONDS) >= 0
                && value.compareTo(MAX_ACCOUNT_GROUP_SEND_INTERVAL_SECONDS) <= 0
                && value.stripTrailingZeros().scale() <= 1);
    }

    private static int normalizeAccountGroupSendIntervalMs(BigDecimal value) {
        if (value == null) {
            return DEFAULT_ACCOUNT_GROUP_SEND_INTERVAL_MS;
        }
        return value.movePointRight(3).intValueExact();
    }

    private static BigDecimal accountGroupSendIntervalSeconds(Integer milliseconds) {
        int normalized = milliseconds == null ? DEFAULT_ACCOUNT_GROUP_SEND_INTERVAL_MS : milliseconds;
        return BigDecimal.valueOf(normalized, 3).stripTrailingZeros();
    }

    private static String snapshotName(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static int distinctAccountCount(List<MarketingTaskTarget> targets) {
        return (int) targets.stream().map(MarketingTaskTarget::getAccountId).distinct().count();
    }

    private Map<Long, MarketingTemplate> loadTemplatesById(List<MarketingTask> tasks) {
        List<Long> templateIds = tasks.stream()
                .map(MarketingTask::getMarketingTemplateId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (templateIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, MarketingTemplate> templatesById = new LinkedHashMap<>();
        for (MarketingTemplate template : templateMapper.selectByIds(templateIds)) {
            templatesById.put(template.getId(), template);
        }
        return templatesById;
    }

    private MarketingTaskVO toVO(MarketingTask task) {
        MarketingTemplate template = task.getMarketingTemplateId() == null
                ? null
                : templateMapper.selectById(task.getMarketingTemplateId());
        return toVO(task, template);
    }

    private static MarketingTaskVO toVO(MarketingTask task, MarketingTemplate template) {
        return new MarketingTaskVO(task.getId(), task.getTaskName(), task.getAccountGroupId(), task.getAccountGroupName(),
                task.getMarketingTemplateId(), task.getMarketingTemplateName(), task.getStatus(),
                task.getSelectedAccountCount(), task.getTargetGroupCount(), task.getTargetPairCount(),
                task.getSentMessageCount(), task.getFailedMessageCount(), task.getSendPerRound(),
                accountGroupSendIntervalSeconds(task.getAccountGroupSendIntervalMs()),
                task.getSendIntervalSeconds(), task.getOnlineCheckEnabled(), task.getAbnormalGroupSkipped(),
                task.getAutoRetryEnabled(), task.getRetryLimit(), task.getRemark(),
                task.getAccountGroupSendAt(), task.getTaskStartAt(), task.getTaskEndAt(), task.getStartedAt(),
                task.getLastSentAt(), task.getFinishedAt(), task.getCreatedAt(), task.getUpdatedAt(),
                template == null ? null : template.getContent(),
                template == null ? null : template.getBodyText(),
                template == null ? null : templatePromotionLink(template).orElse(null));
    }

    private static Optional<String> templatePromotionLink(MarketingTemplate template) {
        if (template.getLinkMode() == null || template.getLinkMode() != LinkMode.BUTTON.code()) {
            return Optional.ofNullable(template.getPromotionLink());
        }
        return MarketingTemplateConverter.buttonsFromJson(template.getButtons()).stream()
                .filter(button -> button.type() == ButtonType.LINK_JUMP)
                .map(button -> button.param())
                .filter(StringUtils::hasText)
                .findFirst();
    }

    private static MarketingTaskTargetVO toTargetVO(MarketingTaskTarget target) {
        return new MarketingTaskTargetVO(target.getId(), target.getAccountId(), target.getAccountPhone(),
                MarketingTargetScope.apiValueOf(target.getTargetScope()), target.getGroupLinkId(),
                target.getGroupJid(), target.getGroupLinkUrl(), target.getGroupName(),
                target.getStatus(), target.getSentMessageCount(), target.getFailedMessageCount(), target.getRetryCount(),
                target.getLastAttemptAt(), target.getLastSentAt(), target.getLastReason());
    }

    private static List<MarketingTaskAccountTargetVO> toAccountTargets(List<MarketingTaskTargetVO> targets,
                                                                       List<MarketingTaskAccountGroupStatRow> groupStats,
                                                                       Map<Long, Integer> loginStates) {
        Map<Long, MarketingTaskTargetVO> targetByAccount = new LinkedHashMap<>();
        for (MarketingTaskTargetVO target : targets) {
            targetByAccount.putIfAbsent(target.accountId(), target);
        }
        Map<Long, List<MarketingTaskAccountGroupStatRow>> statsByAccount = new LinkedHashMap<>();
        for (MarketingTaskAccountGroupStatRow row : groupStats) {
            statsByAccount.computeIfAbsent(row.getAccountId(), ignored -> new ArrayList<>()).add(row);
        }
        return targetByAccount.values().stream()
                .map(target -> toAccountTarget(
                        target,
                        statsByAccount.getOrDefault(target.accountId(), List.of()),
                        loginStates.get(target.accountId())))
                .toList();
    }

    private static MarketingTaskAccountTargetVO toAccountTarget(MarketingTaskTargetVO target,
                                                                List<MarketingTaskAccountGroupStatRow> rows,
                                                                Integer loginState) {
        List<MarketingTaskGroupStatVO> groups = rows.stream()
                .map(MarketingTaskServiceImpl::toGroupStatVO)
                .toList();
        int sent = rows.stream().mapToInt(row -> zero(row.getSentMessageCount())).sum();
        int failed = rows.stream().mapToInt(row -> zero(row.getFailedMessageCount())).sum();
        int skipped = rows.stream().mapToInt(row -> zero(row.getSkippedMessageCount())).sum();
        int status = MarketingTaskAccountStatusResolver.resolve(target.status(), sent, failed);
        return new MarketingTaskAccountTargetVO(target.accountId(), target.accountPhone(), loginState, status,
                sent, failed, skipped, latestAttemptAt(rows), latestSentAt(rows), latestReason(rows), groups);
    }

    private static MarketingTaskGroupStatVO toGroupStatVO(MarketingTaskAccountGroupStatRow row) {
        MarketingGroupExecutionNormalizer.NormalizedExecution execution =
                MarketingGroupExecutionNormalizer.normalize(
                        row.getLatestAttemptStatus(),
                        row.getReasonCode(),
                        row.getReasonMessage(),
                        row.getGroupStatus(),
                        row.getGroupStatusReason());
        String executionResult = MarketingGroupExecutionNormalizer.executionResult(
                row.getLatestExecutionStatus());
        String executionReason;
        if (Integer.valueOf(MarketingSendAttemptStatus.FAILED.code())
                .equals(row.getLatestExecutionStatus())) {
            executionReason = MarketingGroupExecutionNormalizer.normalize(
                    row.getLatestExecutionStatus(),
                    row.getExecutionReasonCode(),
                    row.getExecutionReasonMessage(),
                    row.getExecutionGroupStatus(),
                    row.getExecutionGroupStatusReason()).executionReason();
        } else {
            executionReason = MarketingGroupExecutionNormalizer.executionReason(
                    row.getLatestExecutionStatus(),
                    row.getExecutionReasonMessage(),
                    row.getExecutionReasonCode());
        }
        AccountGroupMembershipStatus membershipStatus = resolveMembershipStatus(row, execution);
        return new MarketingTaskGroupStatVO(row.getGroupLinkId(), row.getGroupJid(), row.getGroupLinkUrl(),
                row.getGroupName(), membershipStatus.apiValue(), execution.groupStatus(), executionResult,
                executionReason, zero(row.getSentMessageCount()), zero(row.getFailedMessageCount()),
                zero(row.getSkippedMessageCount()),
                row.getLastAttemptAt(), row.getLastSentAt(), row.getLastReason());
    }

    private static AccountGroupMembershipStatus resolveMembershipStatus(
            MarketingTaskAccountGroupStatRow row,
            MarketingGroupExecutionNormalizer.NormalizedExecution execution) {
        if (row.getMembershipStatus() != null) {
            return AccountGroupMembershipStatus.fromCode(row.getMembershipStatus());
        }
        if (Integer.valueOf(MarketingSendAttemptStatus.SKIPPED.code())
                .equals(row.getLatestExecutionStatus())) {
            String reasonCode = row.getExecutionReasonCode();
            if (reasonCode != null) {
                return switch (reasonCode.trim().toUpperCase(Locale.ROOT)) {
                    case "KICKED_OUT" -> AccountGroupMembershipStatus.KICKED_OUT;
                    case "LEFT" -> AccountGroupMembershipStatus.LEFT;
                    case "NOT_IN_GROUP" -> AccountGroupMembershipStatus.NOT_IN_GROUP;
                    default -> AccountGroupMembershipStatus.UNCONFIRMED;
                };
            }
        }
        return "KICKED_OUT".equals(execution.groupStatus())
                ? AccountGroupMembershipStatus.KICKED_OUT
                : AccountGroupMembershipStatus.UNCONFIRMED;
    }

    private static Long latestAttemptAt(List<MarketingTaskAccountGroupStatRow> rows) {
        return rows.stream()
                .map(MarketingTaskAccountGroupStatRow::getLastAttemptAt)
                .filter(value -> value != null)
                .max(Long::compareTo)
                .orElse(null);
    }

    private static Long latestSentAt(List<MarketingTaskAccountGroupStatRow> rows) {
        return rows.stream()
                .map(MarketingTaskAccountGroupStatRow::getLastSentAt)
                .filter(value -> value != null)
                .max(Long::compareTo)
                .orElse(null);
    }

    private static String latestReason(List<MarketingTaskAccountGroupStatRow> rows) {
        return rows.stream()
                .filter(row -> StringUtils.hasText(row.getLastReason()))
                .max(Comparator.comparing(row -> row.getLastAttemptAt() == null ? 0L : row.getLastAttemptAt()))
                .map(MarketingTaskAccountGroupStatRow::getLastReason)
                .orElse(null);
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }

    private static MarketingTaskDetailVO toDetailVO(MarketingTask task,
                                                    List<MarketingTaskTargetVO> targets,
                                                    List<MarketingTaskAccountTargetVO> accountTargets) {
        return new MarketingTaskDetailVO(task.getId(), task.getTaskName(), task.getAccountGroupId(), task.getAccountGroupName(),
                task.getMarketingTemplateId(), task.getMarketingTemplateName(), task.getStatus(),
                task.getSelectedAccountCount(), task.getTargetGroupCount(), task.getTargetPairCount(),
                task.getSentMessageCount(), task.getFailedMessageCount(),
                accountTargets.stream().mapToInt(target -> zero(target.skippedMessageCount())).sum(),
                task.getSendPerRound(),
                accountGroupSendIntervalSeconds(task.getAccountGroupSendIntervalMs()),
                task.getSendIntervalSeconds(), task.getOnlineCheckEnabled(), task.getAbnormalGroupSkipped(),
                task.getAutoRetryEnabled(), task.getRetryLimit(), task.getRemark(),
                task.getAccountGroupSendAt(), task.getTaskStartAt(), task.getTaskEndAt(), task.getStartedAt(),
                task.getLastSentAt(), task.getFinishedAt(), task.getCreatedAt(), task.getUpdatedAt(),
                targets, accountTargets);
    }

}
