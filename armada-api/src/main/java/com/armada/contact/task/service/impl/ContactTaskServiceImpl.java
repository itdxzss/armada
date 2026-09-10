package com.armada.contact.task.service.impl;

import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.contact.task.model.dto.ContactTaskQuery;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.enums.ContactTaskAction;
import com.armada.contact.task.model.enums.ContactTaskRunStatus;
import com.armada.contact.task.model.vo.ContactTaskAccountItemVO;
import com.armada.contact.task.model.vo.ContactTaskDetailVO;
import com.armada.contact.task.model.vo.ContactTaskListItemVO;
import com.armada.contact.task.service.ContactAccountSelector;
import com.armada.contact.task.service.ContactTaskExpansionService;
import com.armada.contact.task.service.ContactTaskFormValidator;
import com.armada.contact.task.service.ContactTaskService;
import com.armada.contact.task.service.ContactTaskStatsService;
import com.armada.contact.task.model.vo.ContactTaskStatsVO;
import com.armada.contact.task.service.ContactTaskStateMachine;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 通讯录营销任务业务实现。
 *
 * <p>启用时固定账号范围；Android 名单由后台轮次异步准备，账号数据展示准备和发送状态。</p>
 *
 * <p><b>本类刻意不标注 {@code @Service}</b>：构造参数里有 Supplier，Spring 无法自动装配，
 * 由 {@code ContactTaskConfiguration} 显式构造。这样本类能用纯 Mockito 测试，
 * 不必起 Spring 上下文。</p>
 */
public class ContactTaskServiceImpl implements ContactTaskService {

    private static final String START_MODE_SCHEDULED = "scheduled";
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final int DELETE_BATCH_SIZE_MAX = 200;
    private static final Set<Integer> DELETABLE_STATUSES = Set.of(
            ContactTaskRunStatus.NOT_STARTED.code(), ContactTaskRunStatus.COMPLETED.code(),
            ContactTaskRunStatus.STOPPED.code());

    private final ContactFriendTaskMapper taskMapper;
    private final ContactTaskStatsService statsService;
    private final ContactTaskFormValidator validator;
    private final ContactTaskExpansionService expansionService;
    private final ContactAccountSelector accountSelector;
    private final Supplier<Long> tenantSupplier;
    private final LongSupplier clock;

    /**
     * 创建通讯录营销任务服务。
     *
     * @param taskMapper 任务主表数据访问
     * @param statsService 任务和账号统计查询
     * @param validator 表单校验器
     * @param accountSelector 账号圈选与筛选归一化，与超链任务共用
     * @param expansionService 启用时的圈号与收件人展开服务
     * @param tenantSupplier 当前租户提供者
     * @param clock 当前时间提供者（epoch 毫秒）
     */
    public ContactTaskServiceImpl(
            ContactFriendTaskMapper taskMapper,
            ContactTaskStatsService statsService,
            ContactTaskFormValidator validator,
            ContactTaskExpansionService expansionService,
            ContactAccountSelector accountSelector,
            Supplier<Long> tenantSupplier,
            LongSupplier clock) {
        this.taskMapper = taskMapper;
        this.statsService = statsService;
        this.validator = validator;
        this.expansionService = expansionService;
        this.accountSelector = accountSelector;
        this.tenantSupplier = tenantSupplier;
        this.clock = clock;
    }

    @Override
    public int previewAccountCount(String accountFilterJson) {
        // 走同一个归一化器再交给同一个圈号服务计数：任何一处走岔，界面显示的命中数就会骗人。
        return accountSelector.count(accountFilterJson);
    }

    /**
     * 锁定后校验整批任务并软删除，与调度启动及发送轮次共享任务行锁。
     *
     * @param ids 当前租户待删除的任务 ID，最多 200 个
     * @return 去重后的删除数量
     * @throws BusinessException 参数非法、任务不存在或状态不允许时整批回滚
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > DELETE_BATCH_SIZE_MAX
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择 1 至 200 个有效任务 ID");
        }
        List<Long> taskIds = ids.stream().distinct().sorted().toList();
        // 固定锁顺序避免两次批量删除按相反顺序互相等待；租户条件由 MyBatis 插件注入。
        for (Long id : taskIds) {
            ContactFriendTask task = taskMapper.selectByIdForUpdate(id);
            if (task == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或已删除，请刷新后重试");
            }
            if (!DELETABLE_STATUSES.contains(task.getRunStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "任务 " + id + " 当前状态不允许删除，请先停止任务后重试");
            }
        }
        int deleted = taskMapper.softDeleteBatch(taskIds, clock.getAsLong());
        if (deleted != taskIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务状态已变更，请刷新后重试");
        }
        return deleted;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<ContactTaskListItemVO> list(ContactTaskQuery query) {
        ContactTaskQuery effective = query == null
                ? new ContactTaskQuery(null, null, null, null, null, null)
                : query;
        long total = taskMapper.countPage(effective);
        List<ContactFriendTask> tasks = taskMapper.selectPage(effective);
        var stats = statsService.forTasks(tasks);
        List<ContactTaskListItemVO> rows = tasks.stream()
                .map(row -> toListItem(row, stats.get(row.getId())))
                .toList();
        return PageResult.of(rows, effective.pageOrDefault(), effective.pageSizeOrDefault(), total);
    }

    @Override
    public ContactTaskDetailVO detail(Long id) {
        return toDetail(requireTask(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContactTaskDetailVO create(ContactTaskFormDTO form, Long createdBy) {
        ContactTaskFormDTO normalized = validator.validate(form);
        long now = clock.getAsLong();
        ContactFriendTask row = new ContactFriendTask();
        applyForm(row, normalized, accountSelector.normalizeToJson(normalized.accountFilterJson()), now);
        row.setTenantId(tenantSupplier.get());
        row.setMessageType(normalized.messageType());
        row.setRunStatus(ContactTaskRunStatus.NOT_STARTED.code());
        row.setCreatedBy(createdBy);
        row.setCreatedAt(now);
        taskMapper.insert(row);
        // 启用态创建：先落库拿到 id，再圈号展开；草稿不展开
        if (isEnabled(row)) {
            expansionService.expand(row);
        }
        return toDetail(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContactTaskDetailVO update(Long id, ContactTaskFormDTO form) {
        ContactFriendTask existing = requireTask(id);
        ContactTaskRunStatus runStatus = ContactTaskRunStatus.fromCode(existing.getRunStatus());
        if (!ContactTaskStateMachine.isEditable(runStatus)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "任务已开始，仅可查看不能修改");
        }
        ContactTaskFormDTO normalized = validator.validate(form);
        if (!normalized.messageType().equals(existing.getMessageType())) {
            throw new BusinessException(ErrorCode.VALIDATION, "消息类型创建后不可修改");
        }
        long now = clock.getAsLong();
        // applyForm 会覆盖 isEnabled，旧值必须在覆盖前取
        boolean wasEnabled = isEnabled(existing);
        applyForm(existing, normalized, accountSelector.normalizeToJson(normalized.accountFilterJson()), now);
        // 删除可能在表单读取后提交；未更新成功时不能继续展开账号与收件人。
        if (taskMapper.updateForm(existing) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已变更或删除，请刷新后重试");
        }
        // 只有草稿被打开时才展开；已启用任务重复保存不再圈一遍号
        if (!wasEnabled && isEnabled(existing)) {
            expansionService.expand(existing);
        }
        return toDetail(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void action(Long id, String action) {
        ContactFriendTask existing = requireTask(id);
        ContactTaskAction parsed;
        try {
            parsed = ContactTaskAction.fromWire(action);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, ex.getMessage());
        }
        ContactTaskRunStatus current = ContactTaskRunStatus.fromCode(existing.getRunStatus());
        Optional<ContactTaskRunStatus> target = ContactTaskStateMachine.next(current, parsed);
        if (target.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "当前状态不允许该动作: " + action);
        }
        long now = clock.getAsLong();
        // 只有进入「进行中」才需要排下一轮；其余状态清空调度时间避免被调度器捞起来
        Long nextRoundAt = target.get() == ContactTaskRunStatus.RUNNING ? now : null;
        int updated = taskMapper.updateRunStatus(
                id, current.code(), target.get().code(), nextRoundAt, now);
        if (updated == 0) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "任务状态已变更，请刷新后重试");
        }
    }

    @Override
    public PageResult<ContactTaskAccountItemVO> accountData(
            Long id, String sortBy, String sortOrder, Integer page, Integer pageSize) {
        return statsService.accounts(id, sortBy, sortOrder, page, pageSize);
    }

    private ContactFriendTask requireTask(Long id) {
        ContactFriendTask task = id == null ? null : taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "通讯录营销任务不存在: " + id);
        }
        return task;
    }

    /** 把归一化后的表单写进实体，并按启动方式推导计划开始时间。 */
    private static void applyForm(
            ContactFriendTask row, ContactTaskFormDTO form, String normalizedFilter, long now) {
        row.setName(form.name());
        row.setAccountFilter(normalizedFilter);
        row.setTitle(form.title());
        row.setDescription(form.description());
        row.setPromotionLink(form.promotionLink());
        row.setContent(form.content());
        // 传 null 即清空配图：编辑时用户删掉图不能保留旧图。
        row.setPreviewImageFileId(form.previewImageFileId());
        row.setMsgIntervalMinSec(form.msgIntervalMinSec());
        row.setMsgIntervalMaxSec(form.msgIntervalMaxSec());
        row.setConcurrency(form.concurrency());
        row.setMaxSendsPerAccount(form.maxSendsPerAccount());
        row.setRetryMax(form.retryMax());
        row.setStartMode(form.startMode());
        row.setTaskDelayMinutes(form.taskDelayMinutes());
        row.setIsEnabled(form.isEnabled());
        row.setUpdatedAt(now);
        row.setTaskStartAt(resolveStartAt(form, now));
    }

    /** 未启用的任务没有计划开始时间；延后模式按分钟数推算。 */
    private static Long resolveStartAt(ContactTaskFormDTO form, long now) {
        if (form.isEnabled() == null || form.isEnabled() != 1) {
            return null;
        }
        if (START_MODE_SCHEDULED.equals(form.startMode())) {
            return now + form.taskDelayMinutes() * MILLIS_PER_MINUTE;
        }
        return now;
    }

    private static ContactTaskListItemVO toListItem(ContactFriendTask row, ContactTaskStatsVO stats) {
        return new ContactTaskListItemVO(
                row.getId(), row.getName(), row.getMessageType(), row.getTitle(),
                row.getContent(), row.getPromotionLink(), row.getAccountFilter(),
                row.getIsEnabled(),
                row.getRunStatus(), row.getTotalSendNum(), row.getSuccessMessageNum(),
                row.getUsedAccountCount(), row.getInvalidAccountNum(),
                row.getAvgSendPerAccount(), row.getTaskStartAt(), row.getCreatedAt(), stats);
    }

    private static ContactTaskDetailVO toDetail(ContactFriendTask row) {
        return new ContactTaskDetailVO(
                row.getId(), row.getName(), row.getMessageType(), row.getTitle(),
                row.getDescription(), row.getPromotionLink(), row.getContent(),
                row.getPreviewImageFileId(), row.getAccountFilter(),
                row.getMsgIntervalMinSec(), row.getMsgIntervalMaxSec(),
                row.getConcurrency(), row.getMaxSendsPerAccount(), row.getRetryMax(),
                row.getStartMode(), row.getTaskDelayMinutes(), row.getTaskStartAt(),
                row.getIsEnabled(), row.getRunStatus(),
                zeroIfNull(row.getTotalSendNum()), zeroIfNull(row.getSuccessMessageNum()),
                zeroIfNull(row.getUsedAccountCount()), zeroIfNull(row.getInvalidAccountNum()),
                row.getAvgSendPerAccount() == null ? BigDecimal.ZERO : row.getAvgSendPerAccount(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    /** 只有 is_enabled=1 才算启用；null 与 0 都是草稿。 */
    private static boolean isEnabled(ContactFriendTask row) {
        return row.getIsEnabled() != null && row.getIsEnabled() == 1;
    }

    private static Integer zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }
}
