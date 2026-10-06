package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.mapper.PullTaskDirectLinkCreateMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskDirectCreateRequest;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.service.PullTaskStandardStartService;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 直接链接正式创建编排；外部预检不持事务，自动启动发生在建单提交后。 */
@Service
public class PullTaskDirectLinkCreateService {
    private static final int AUTO_START_YES = 1;
    private final PullTaskDirectLinkPlanner planner;
    private final PullTaskDirectLinkCreateTransactionService transactions;
    private final PullTaskDirectLinkCreateMapper tasks;
    private final PullTaskStandardSettingMapper settings;
    private final PullTaskStandardStartService start;

    /** 创建编排所需的来源、事务与正式任务启动能力。 */
    public PullTaskDirectLinkCreateService(PullTaskDirectLinkPlanner planner,
            PullTaskDirectLinkCreateTransactionService transactions, PullTaskDirectLinkCreateMapper tasks,
            PullTaskStandardSettingMapper settings, PullTaskStandardStartService start) {
        this.planner = planner;
        this.transactions = transactions;
        this.tasks = tasks;
        this.settings = settings;
        this.start = start;
    }

    /** 同用户同 requestId 始终返回原任务；发生竞争时只在失败事务回滚后读回。 */
    public PullTaskStandardCreatedVO create(PullTaskDirectCreateRequest request,
            List<MultipartFile> files, AuthPrincipal principal) {
        PullTaskDirectLinkPlanner.validate(request);
        if (request.packageIds() != null && !request.packageIds().isEmpty()
                && !principal.permissions().contains("tenant:group_data_package:view")) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "没有拉群数据包查看权限");
        }
        String requestId = request.requestId().toLowerCase(Locale.ROOT);
        PullTask task = tasks.selectByRequest(principal.userId(), requestId);
        if (task == null) {
            try {
                var plan = planner.plan(request, files);
                task = transactions.create(request, principal, plan);
            } catch (BusinessException | DuplicateKeyException conflict) {
                // 同请求可能在本次来源预检期间已提交并占用链接，按请求身份读回，不能误报料子冲突。
                task = tasks.selectByRequest(principal.userId(), requestId);
                if (task == null) {
                    if (conflict instanceof BusinessException business) {
                        throw business;
                    }
                    throw new BusinessException(ErrorCode.CONFLICT, "群链接或数据包已被其他任务占用，请刷新后重试");
                }
            }
        }
        if (task.getCreationMode() != request.frozenSettings().creationMode()) {
            throw new BusinessException(ErrorCode.CONFLICT, "创建请求已用于其他模式，请重新创建");
        }
        if (task.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "该创建请求的任务已删除，请重新创建");
        }
        var setting = settings.selectByTaskId(task.getId());
        if (setting == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "正式任务执行配置不存在");
        }
        if (PullTaskStandardStatus.WAIT_START.name().equals(task.getStatus())
                && Integer.valueOf(AUTO_START_YES).equals(setting.getAutoStart())) {
            task = startOrReadStarted(task, principal.userId(), requestId);
        }
        return new PullTaskStandardCreatedVO(task.getId(), task.getTaskName(), task.getStatus(),
                task.getGroupCount(), task.getExpectedPullCount());
    }

    private PullTask startOrReadStarted(PullTask task, long userId, String requestId) {
        try {
            start.start(task.getId());
        } catch (BusinessException conflict) {
            if (conflict.getCode() != ErrorCode.CONFLICT.code()) {
                throw conflict;
            }
            PullTask current = tasks.selectByRequest(userId, requestId);
            // 同请求并发启动的乐观锁冲突，只能以已提交的启动事实收敛。
            if (current == null || current.getDeletedAt() != null || current.getStartedAt() == null
                    || PullTaskStandardStatus.WAIT_START.name().equals(current.getStatus())) {
                throw conflict;
            }
            return current;
        }
        return tasks.selectByRequest(userId, requestId);
    }
}
