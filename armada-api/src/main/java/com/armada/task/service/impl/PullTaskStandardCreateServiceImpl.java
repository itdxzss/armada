package com.armada.task.service.impl;

import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.service.PullTaskStandardCreateService;
import com.armada.task.service.PullTaskStandardStartService;
import org.springframework.stereotype.Service;

/** 普通群链接任务整单提交与提交后自动启动编排。 */
@Service
public class PullTaskStandardCreateServiceImpl implements PullTaskStandardCreateService {

    private static final int AUTO_START_YES = 1;

    private final PullTaskStandardCreateTransactionService transactionService;
    private final PullTaskMapper pullTaskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskStandardStartService startService;

    /** 创建整单提交编排服务。 */
    public PullTaskStandardCreateServiceImpl(
            PullTaskStandardCreateTransactionService transactionService,
            PullTaskMapper pullTaskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskStandardStartService startService) {
        this.transactionService = transactionService;
        this.pullTaskMapper = pullTaskMapper;
        this.settingMapper = settingMapper;
        this.startService = startService;
    }

    /** {@inheritDoc} */
    @Override
    public PullTaskStandardCreatedVO create(PullTaskStandardCreateDTO request, long userId) {
        PullTaskStandardCreateTransactionService.SubmissionResult result =
                transactionService.submit(request, userId);
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(result.created().id());
        if (!"WAIT_START".equals(result.created().status())
                || setting == null || setting.getAutoStart() != AUTO_START_YES) {
            return result.created();
        }
        // transactionService 是独立 Spring Bean；方法返回时提交事务已经完成。
        startService.start(result.created().id());
        PullTask started = pullTaskMapper.selectLifecycle(result.created().id());
        return toCreatedVO(started);
    }

    private static PullTaskStandardCreatedVO toCreatedVO(PullTask task) {
        return new PullTaskStandardCreatedVO(task.getId(), task.getTaskName(), task.getStatus(),
                task.getGroupCount(), task.getExpectedPullCount());
    }
}
