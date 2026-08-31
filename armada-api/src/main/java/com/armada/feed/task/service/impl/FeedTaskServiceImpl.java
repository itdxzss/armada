package com.armada.feed.task.service.impl;

import com.armada.feed.task.mapper.FeedTaskAccountMapper;
import com.armada.feed.task.mapper.FeedTaskMapper;
import com.armada.feed.task.model.dto.FeedTaskFormDTO;
import com.armada.feed.task.model.dto.FeedTaskQuery;
import com.armada.feed.task.model.entity.FeedTask;
import com.armada.feed.task.model.entity.FeedTaskAccount;
import com.armada.feed.task.model.enums.FeedTaskAction;
import com.armada.feed.task.model.enums.FeedTaskRunStatus;
import com.armada.feed.task.model.vo.FeedTaskAccountVO;
import com.armada.feed.task.model.vo.FeedTaskVO;
import com.armada.feed.task.service.FeedTaskAccountSelector;
import com.armada.feed.task.service.FeedTaskExpansionService;
import com.armada.feed.task.service.FeedTaskService;
import com.armada.feed.task.service.FeedTaskStateMachine;
import com.armada.marketing.model.vo.MarketingTemplateFileVO;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 动态发布任务业务实现。 */
@Service
public class FeedTaskServiceImpl implements FeedTaskService {

    private static final int ENABLED = 1;
    private static final int DISABLED = 0;
    private static final String START_MODE_NOW = "now";
    private static final String START_MODE_SCHEDULED = "scheduled";
    private static final String TASK_MODE_INSTANT = "instant";
    private static final String TASK_MODE_ROLLING = "rolling";
    private static final int DEFAULT_CONCURRENCY = 10;
    private static final int DEFAULT_RETRY_MAX = 3;
    private static final int ACCOUNT_PAGE_SIZE_MAX = 200;
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final FeedTaskMapper taskMapper;
    private final FeedTaskAccountMapper accountMapper;
    private final FeedTaskAccountSelector accountSelector;
    private final FeedTaskExpansionService expansionService;
    private final MarketingTemplateFileService fileService;

    public FeedTaskServiceImpl(FeedTaskMapper taskMapper,
                               FeedTaskAccountMapper accountMapper,
                               FeedTaskAccountSelector accountSelector,
                               FeedTaskExpansionService expansionService,
                               MarketingTemplateFileService fileService) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.accountSelector = accountSelector;
        this.expansionService = expansionService;
        this.fileService = fileService;
    }

    @Override
    public PageResult<FeedTaskVO> list(FeedTaskQuery query) {
        FeedTaskQuery effective = query == null ? new FeedTaskQuery() : query;
        long total = taskMapper.countPage(effective);
        List<FeedTaskVO> rows = total == 0 ? List.of() : taskMapper.selectPage(effective).stream()
                .map(this::toVO)
                .toList();
        return PageResult.of(rows, effective.getPage(), effective.getPageSize(), total);
    }

    @Override
    public FeedTaskVO detail(Long id) {
        return toVO(requireTask(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedTaskVO create(FeedTaskFormDTO form, MultipartFile linkPreviewImage, Long createdBy) {
        FeedTask row = new FeedTask();
        long now = System.currentTimeMillis();
        applyForm(row, form, null, linkPreviewImage, now);
        row.setTenantId(TenantContext.get());
        row.setTaskStatus(FeedTaskRunStatus.NOT_STARTED.code());
        row.setCurrentRoundNo(0L);
        row.setTotalAccountNum(0);
        row.setSuccessAccountNum(0);
        row.setFailedAccountNum(0);
        row.setCreatedBy(createdBy);
        row.setCreatedAt(now);
        taskMapper.insert(row);
        expandWhenEnabled(row, now);
        return toVO(taskMapper.selectById(row.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedTaskVO update(Long id, FeedTaskFormDTO form, MultipartFile linkPreviewImage) {
        FeedTask existing = requireTask(id);
        FeedTaskRunStatus runStatus = FeedTaskRunStatus.fromCode(existing.getTaskStatus());
        if (!FeedTaskStateMachine.isEditable(runStatus)) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已开始，仅可查看不能修改");
        }
        boolean wasEnabled = enabled(existing);
        applyForm(existing, form, existing.getLinkPreviewImageFileId(), linkPreviewImage,
                System.currentTimeMillis());
        taskMapper.updateForm(existing);
        if (!wasEnabled && enabled(existing)) {
            expandWhenEnabled(existing, System.currentTimeMillis());
        }
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedTaskVO action(Long id, String action) {
        FeedTask existing = requireTask(id);
        FeedTaskAction parsed;
        try {
            parsed = FeedTaskAction.fromWire(action);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, ex.getMessage());
        }
        if (!enabled(existing)) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务未启用，不能执行动作");
        }
        FeedTaskRunStatus current = FeedTaskRunStatus.fromCode(existing.getTaskStatus());
        Optional<FeedTaskRunStatus> target = FeedTaskStateMachine.next(current, parsed);
        if (target.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前状态不允许该动作: " + action);
        }
        long now = System.currentTimeMillis();
        if (parsed == FeedTaskAction.START) {
            expandWhenEnabled(existing, now);
        }
        Long nextRunAt = target.get() == FeedTaskRunStatus.RUNNING ? now : null;
        int updated = taskMapper.updateRunStatus(id, current.code(), target.get().code(), nextRunAt, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务状态已变更，请刷新后重试");
        }
        return detail(id);
    }

    @Override
    public PageResult<FeedTaskAccountVO> accountData(
            Long id, String accountPhone, Integer page, Integer pageSize) {
        requireTask(id);
        int effectivePage = page == null || page < 1 ? 1 : page;
        int effectiveSize = pageSize == null || pageSize < 1
                ? 20
                : Math.min(pageSize, ACCOUNT_PAGE_SIZE_MAX);
        String phone = accountPhone == null || accountPhone.isBlank() ? null : accountPhone.trim();
        long total = accountMapper.countPage(id, phone);
        List<FeedTaskAccountVO> rows = total == 0 ? List.of() : accountMapper.selectPage(
                        id, phone, (effectivePage - 1) * effectiveSize, effectiveSize).stream()
                .map(FeedTaskServiceImpl::toAccountVO)
                .toList();
        return PageResult.of(rows, effectivePage, effectiveSize, total);
    }

    private FeedTask requireTask(Long id) {
        FeedTask task = id == null ? null : taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "动态发布任务不存在: " + id);
        }
        return task;
    }

    private void applyForm(FeedTask row,
                           FeedTaskFormDTO form,
                           Long existingFileId,
                           MultipartFile linkPreviewImage,
                           long now) {
        if (form == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "动态发布任务表单不能为空");
        }
        String name = required(form.getName(), "任务名称不能为空");
        String title = required(form.getTitle(), "推广标题不能为空");
        String promotionLink = required(form.getPromotionLink(), "推广链接不能为空");
        String content = optional(form.getContent());
        if (content == null && optional(form.getDescription()) == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "动态内容不能为空");
        }
        String taskMode = taskMode(form.getTaskMode());
        Long plannedEndAt = TASK_MODE_ROLLING.equals(taskMode)
                ? parseTime(form.getTaskPlannedEndAt(), "taskPlannedEndAt")
                : null;
        if (TASK_MODE_ROLLING.equals(taskMode) && (plannedEndAt == null || plannedEndAt <= now)) {
            throw new BusinessException(ErrorCode.VALIDATION, "预发布任务计划结束时间必须晚于当前时间");
        }
        int status = status(form.getStatus());
        int delayMinutes = delayMinutes(form.getTaskDelayMinutes());
        Long imageFileId = uploadFileId(linkPreviewImage, existingFileId);

        row.setName(name);
        row.setAccountFilter(accountSelector.normalizeToJson(form.getAccountFilter()));
        row.setTitle(title);
        row.setDescription(optional(form.getDescription()));
        row.setContent(content);
        row.setPromotionLink(promotionLink);
        row.setLinkPreviewImageFileId(imageFileId);
        row.setTextColor(optional(form.getTextColor()) == null ? "#FFFFFF" : form.getTextColor().trim());
        row.setBackgroundColor(optional(form.getBackgroundColor()) == null ? "#075E54" : form.getBackgroundColor().trim());
        row.setConcurrency(clamp(form.getConcurrency(), DEFAULT_CONCURRENCY, 1, 200));
        row.setRetryMax(clamp(form.getRetryMax(), DEFAULT_RETRY_MAX, 0, 10));
        row.setStartMode(delayMinutes > 0 ? START_MODE_SCHEDULED : START_MODE_NOW);
        row.setTaskDelayMinutes(delayMinutes);
        row.setTaskStartAt(status == ENABLED ? resolveStartAt(delayMinutes, now) : null);
        row.setTaskMode(taskMode);
        row.setTaskPlannedEndAt(plannedEndAt);
        row.setStatus(status);
        row.setUpdatedAt(now);
    }

    private void expandWhenEnabled(FeedTask row, long now) {
        if (!enabled(row)) {
            return;
        }
        int inserted = expansionService.expand(row, row.getConcurrency(), now);
        if (inserted > 0) {
            taskMapper.incrementTotalAccountNum(row.getId(), inserted, now);
            row.setTotalAccountNum((row.getTotalAccountNum() == null ? 0 : row.getTotalAccountNum()) + inserted);
        }
        if (TASK_MODE_INSTANT.equals(row.getTaskMode()) && (row.getTotalAccountNum() == null || row.getTotalAccountNum() == 0)) {
            throw new BusinessException(ErrorCode.VALIDATION, "即时动态发布任务没有可用账号");
        }
    }

    private Long uploadFileId(MultipartFile file, Long existingFileId) {
        if (file == null || file.isEmpty()) {
            return existingFileId;
        }
        MarketingTemplateFileVO uploaded = fileService.uploadImage(file);
        return uploaded.id();
    }

    private FeedTaskVO toVO(FeedTask row) {
        if (row == null) {
            return null;
        }
        return new FeedTaskVO(
                row.getId(),
                row.getName(),
                accountSelector.toViewFilter(row.getAccountFilter()),
                row.getTitle(),
                row.getDescription(),
                row.getContent(),
                row.getPromotionLink(),
                imageUrl(row.getLinkPreviewImageFileId()),
                row.getTextColor(),
                row.getBackgroundColor(),
                zeroIfNull(row.getConcurrency()),
                zeroIfNull(row.getRetryMax()),
                row.getStartMode(),
                row.getTaskMode(),
                row.getTaskStatus(),
                row.getStatus(),
                row.getTaskDelayMinutes(),
                zeroIfNull(row.getTotalAccountNum()),
                zeroIfNull(row.getSuccessAccountNum()),
                zeroIfNull(row.getFailedAccountNum()),
                iso(row.getTaskStartAt()),
                iso(row.getTaskPlannedEndAt()),
                iso(row.getCreatedAt()));
    }

    private static FeedTaskAccountVO toAccountVO(FeedTaskAccount row) {
        return new FeedTaskAccountVO(
                row.getId(),
                row.getAccountId(),
                row.getAccountPhoneSnapshot(),
                row.getSendStatus(),
                zeroIfNull(row.getRetryNum()),
                zeroIfNull(row.getRetryMax()),
                iso(row.getSendAt()),
                iso(row.getSuccessAt()),
                iso(row.getFailedAt()),
                row.getFailCode(),
                row.getFailReason());
    }

    private static String required(String value, String message) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String taskMode(String value) {
        if (value == null || value.isBlank() || TASK_MODE_INSTANT.equals(value.trim())) {
            return TASK_MODE_INSTANT;
        }
        if (TASK_MODE_ROLLING.equals(value.trim())) {
            return TASK_MODE_ROLLING;
        }
        throw new BusinessException(ErrorCode.VALIDATION, "任务模式非法");
    }

    private static int status(Integer value) {
        if (value == null) {
            return ENABLED;
        }
        if (value == ENABLED || value == DISABLED) {
            return value;
        }
        throw new BusinessException(ErrorCode.VALIDATION, "任务开关非法");
    }

    private static int delayMinutes(Integer value) {
        if (value == null || value <= 0) {
            return 0;
        }
        return value;
    }

    private static Long resolveStartAt(int delayMinutes, long now) {
        return delayMinutes <= 0 ? now : now + delayMinutes * MILLIS_PER_MINUTE;
    }

    private static int clamp(Integer value, int defaultValue, int min, int max) {
        int effective = value == null ? defaultValue : value;
        return Math.max(min, Math.min(max, effective));
    }

    private static boolean enabled(FeedTask row) {
        return row.getStatus() != null && row.getStatus() == ENABLED;
    }

    private static Integer zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    private static String iso(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis).toString();
    }

    private static String imageUrl(Long fileId) {
        return fileId == null ? null : "/api/marketing-template-files/" + fileId + "/content";
    }

    private static Long parseTime(String value, String field) {
        return FeedTaskQuery.parseTime(value, field);
    }
}
