package com.armada.contact.task.service.impl;

import com.armada.account.selection.AccountFilterSelector;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.contact.task.model.dto.ContactTaskQuery;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.enums.ContactTaskAction;
import com.armada.contact.task.model.enums.ContactTaskRunStatus;
import com.armada.contact.task.model.vo.ContactTaskAccountItemVO;
import com.armada.contact.task.model.vo.ContactTaskDetailVO;
import com.armada.contact.task.model.vo.ContactTaskListItemVO;
import com.armada.contact.task.service.ContactAccountFilterNormalizer;
import com.armada.contact.task.service.ContactTaskExpansionService;
import com.armada.contact.task.service.ContactTaskFormValidator;
import com.armada.contact.task.service.ContactTaskService;
import com.armada.contact.task.service.ContactTaskStateMachine;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
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
 * <p>本期只负责任务本身的增查改与状态机；账号圈选、收件人展开和真实发送属于发送引擎，
 * 因此 {@code accountData} 在引擎落地前一直返回空页。</p>
 *
 * <p><b>本类刻意不标注 {@code @Service}</b>：构造参数里有 Supplier，Spring 无法自动装配，
 * 由 {@code ContactTaskConfiguration} 显式构造。这样本类能用纯 Mockito 测试，
 * 不必起 Spring 上下文。</p>
 */
public class ContactTaskServiceImpl implements ContactTaskService {

    /** 账号数据接口允许的排序列白名单，其余一律抹成 null 交给 XML 兜底。 */
    private static final Set<String> SORTABLE_COLUMNS =
            Set.of("needSendNum", "sentNum", "failNum");

    private static final String SORT_ASC = "asc";
    private static final String SORT_DESC = "desc";
    private static final String START_MODE_SCHEDULED = "scheduled";
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final int ACCOUNT_PAGE_SIZE_MAX = 200;

    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactTaskFormValidator validator;
    private final ContactAccountFilterNormalizer filterNormalizer;
    private final ContactTaskExpansionService expansionService;
    private final AccountFilterSelector accountFilterSelector;
    private final Supplier<Long> tenantSupplier;
    private final LongSupplier clock;

    /**
     * 创建通讯录营销任务服务。
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param validator 表单校验器
     * @param filterNormalizer 账号筛选归一化器
     * @param expansionService 启用时的圈号与收件人展开服务
     * @param tenantSupplier 当前租户提供者
     * @param clock 当前时间提供者（epoch 毫秒）
     */
    public ContactTaskServiceImpl(
            ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper,
            ContactTaskFormValidator validator,
            ContactAccountFilterNormalizer filterNormalizer,
            ContactTaskExpansionService expansionService,
            AccountFilterSelector accountFilterSelector,
            Supplier<Long> tenantSupplier,
            LongSupplier clock) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.validator = validator;
        this.filterNormalizer = filterNormalizer;
        this.expansionService = expansionService;
        this.accountFilterSelector = accountFilterSelector;
        this.tenantSupplier = tenantSupplier;
        this.clock = clock;
    }

    @Override
    public int previewAccountCount(String accountFilterJson) {
        // 走同一个归一化器再交给同一个圈号服务计数：任何一处走岔，界面显示的命中数就会骗人。
        return accountFilterSelector.count(filterNormalizer.normalize(accountFilterJson));
    }

    @Override
    public PageResult<ContactTaskListItemVO> list(ContactTaskQuery query) {
        ContactTaskQuery effective = query == null
                ? new ContactTaskQuery(null, null, null, null, null, null)
                : query;
        long total = taskMapper.countPage(effective);
        List<ContactTaskListItemVO> rows = taskMapper.selectPage(effective).stream()
                .map(ContactTaskServiceImpl::toListItem)
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
        applyForm(row, normalized, filterNormalizer.normalize(normalized.accountFilterJson()), now);
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
        applyForm(existing, normalized, filterNormalizer.normalize(normalized.accountFilterJson()), now);
        taskMapper.updateForm(existing);
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
        requireTask(id);
        int effectivePage = page == null || page < 1 ? 1 : page;
        int effectiveSize = pageSize == null || pageSize < 1
                ? 20
                : Math.min(pageSize, ACCOUNT_PAGE_SIZE_MAX);
        String safeSortBy = sortBy != null && SORTABLE_COLUMNS.contains(sortBy) ? sortBy : null;
        String safeSortOrder = SORT_ASC.equalsIgnoreCase(
                sortOrder == null ? "" : sortOrder.trim()) ? SORT_ASC : SORT_DESC;

        long total = accountMapper.countByTaskId(id);
        List<ContactTaskAccountItemVO> rows = accountMapper.selectPage(
                        id, safeSortBy, safeSortOrder,
                        (effectivePage - 1) * effectiveSize, effectiveSize).stream()
                .map(ContactTaskServiceImpl::toAccountItem)
                .toList();
        return PageResult.of(rows, effectivePage, effectiveSize, total);
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

    private static ContactTaskListItemVO toListItem(ContactFriendTask row) {
        return new ContactTaskListItemVO(
                row.getId(), row.getName(), row.getMessageType(), row.getTitle(),
                row.getContent(), row.getPromotionLink(), row.getAccountFilter(),
                row.getIsEnabled(),
                row.getRunStatus(), row.getTotalSendNum(), row.getSuccessMessageNum(),
                row.getUsedAccountCount(), row.getInvalidAccountNum(),
                row.getAvgSendPerAccount(), row.getTaskStartAt(), row.getCreatedAt());
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

    private static ContactTaskAccountItemVO toAccountItem(ContactFriendTaskAccount row) {
        return new ContactTaskAccountItemVO(
                row.getAccountId(),
                row.getAccountPhoneSnapshot(),
                row.getAccountStatusSnapshot(),
                zeroIfNull(row.getNeedSendNum()),
                zeroIfNull(row.getSentNum()),
                zeroIfNull(row.getFailNum()));
    }

    /** 只有 is_enabled=1 才算启用；null 与 0 都是草稿。 */
    private static boolean isEnabled(ContactFriendTask row) {
        return row.getIsEnabled() != null && row.getIsEnabled() == 1;
    }

    private static Integer zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }
}
