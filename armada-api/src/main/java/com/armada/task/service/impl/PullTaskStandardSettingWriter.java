package com.armada.task.service.impl;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.entity.PullTaskStandardSetting;
import org.springframework.stereotype.Service;

/** 创建普通群链接任务时组装并写入冻结配置。 */
@Service
public class PullTaskStandardSettingWriter {

    /** 旧数据模型中的启动期字段；当前创建阶段固定写待处理值 0。 */
    private static final int REQUIRED_MANAGER_COUNT_PENDING = 0;

    private final PullTaskStandardSettingMapper settingMapper;
    private final AccountGroupService accountGroupService;

    /**
     * @param settingMapper      冻结配置 Mapper
     * @param accountGroupService 账号分组领域服务
     */
    public PullTaskStandardSettingWriter(PullTaskStandardSettingMapper settingMapper,
                                         AccountGroupService accountGroupService) {
        this.settingMapper = settingMapper;
        this.accountGroupService = accountGroupService;
    }

    /**
     * 校验三个分组、冻结名称快照并写配置。
     *
     * @param request 创建请求
     * @param taskId  草稿任务 ID
     */
    public void insert(PullTaskStandardCreateDTO request, long taskId) {
        AccountGroup managerGroup = accountGroupService.requireExisting(request.managerGroupId());
        AccountGroup pullerGroup = accountGroupService.requireExisting(request.pullerGroupId());
        AccountGroup stationGroup = accountGroupService.requireExisting(request.stationGroupId());
        long now = System.currentTimeMillis();

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
        setting.setCreatedAt(now);
        setting.setUpdatedAt(now);
        settingMapper.insert(setting);
    }
}
