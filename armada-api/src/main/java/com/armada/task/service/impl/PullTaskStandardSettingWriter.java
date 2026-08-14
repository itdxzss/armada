package com.armada.task.service.impl;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.group.service.GroupFolderService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.entity.PullTaskStandardSetting;
import org.springframework.stereotype.Service;

/** 创建普通群链接任务时组装并写入冻结执行配置。 */
@Service
public class PullTaskStandardSettingWriter {

    private static final int REQUIRED_MANAGER_COUNT_PENDING = 0;

    private final PullTaskStandardSettingMapper settingMapper;
    private final AccountGroupService accountGroupService;
    private final GroupFolderService groupFolderService;

    /** 创建执行设置写入器。 */
    public PullTaskStandardSettingWriter(PullTaskStandardSettingMapper settingMapper,
                                         AccountGroupService accountGroupService,
                                         GroupFolderService groupFolderService) {
        this.settingMapper = settingMapper;
        this.accountGroupService = accountGroupService;
        this.groupFolderService = groupFolderService;
    }

    /** 校验引用、冻结服务端名称快照并写入配置。 */
    public void insert(PullTaskStandardCreateDTO request, long taskId) {
        requireNewFields(request);
        AccountGroup manager = requireAccountGroup(request.managerGroupId(), "管理账号分组");
        AccountGroup puller = requireAccountGroup(request.pullerGroupId(), "拉手账号分组");
        AccountGroup station = stationGroup(request);
        AccountGroup managerFinish = optionalAccountGroup(request.managerFinishGroupId());
        AccountGroup pullerFinish = optionalAccountGroup(request.pullerFinishGroupId());
        GroupFolderOptionVO folder = request.groupFolderId() == null
                ? null : groupFolderService.requireExisting(request.groupFolderId());

        long now = System.currentTimeMillis();
        PullTaskStandardSetting setting = new PullTaskStandardSetting();
        setting.setTaskId(taskId);
        setting.setAutoStart(request.autoStart());
        setting.setSourceGroupFolderId(request.groupFolderId());
        setting.setSourceGroupFolderName(folder == null ? null : folder.name());
        setting.setMaterialAdminTiming(request.materialAdminTiming());
        setting.setPullerSyncMode(request.pullerSyncMode().code());
        setting.setClearExistingMembers(Boolean.TRUE.equals(request.clearExistingMembers()) ? 1 : 0);
        setting.setPullerJoinByLink(Boolean.TRUE.equals(request.pullerJoinByLink()) ? 1 : 0);
        setting.setEarlyPullCount(request.earlyPullCount());
        setting.setEarlyPullCallCount(request.earlyPullCallCount());
        setting.setPullCountMin(request.pullCountMin());
        setting.setPullCountMax(request.pullCountMax());
        setting.setPullIntervalSeconds(request.pullIntervalSeconds());
        setting.setPullerCountPerGroup(request.pullerCountPerGroup());
        setting.setStationCountPerCall(request.stationCountPerCall());
        setting.setConcurrentGroupCount(request.concurrentGroupCount());
        // 原型把拉手风控时间标为“后期”；现有执行列暂存服务端默认值，不暴露给创建合同。
        setting.setPullerRiskMinutes(0);
        setting.setRequiredManagerCount(REQUIRED_MANAGER_COUNT_PENDING);
        setAccountSnapshots(setting, request, manager, puller, station, managerFinish, pullerFinish);
        setting.setCreatedAt(now);
        setting.setUpdatedAt(now);
        settingMapper.insert(setting);
    }

    private void requireNewFields(PullTaskStandardCreateDTO request) {
        if (request.pullerSyncMode() == null || request.clearExistingMembers() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "拉手同步和清群选项不能为空");
        }
    }

    private AccountGroup stationGroup(PullTaskStandardCreateDTO request) {
        if (request.stationCountPerCall() != null && request.stationCountPerCall() > 0
                && request.stationGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "站台数量大于 0 时必须选择站台分组");
        }
        return optionalAccountGroup(request.stationGroupId());
    }

    private AccountGroup requireAccountGroup(Long id, String fieldName) {
        if (id == null) {
            throw new BusinessException(ErrorCode.VALIDATION, fieldName + "不能为空");
        }
        return accountGroupService.requireExisting(id);
    }

    private AccountGroup optionalAccountGroup(Long id) {
        return id == null ? null : accountGroupService.requireExisting(id);
    }

    private static void setAccountSnapshots(
            PullTaskStandardSetting setting,
            PullTaskStandardCreateDTO request,
            AccountGroup manager,
            AccountGroup puller,
            AccountGroup station,
            AccountGroup managerFinish,
            AccountGroup pullerFinish) {
        setting.setManagerGroupId(request.managerGroupId());
        setting.setManagerGroupName(manager.getName());
        setting.setPullerGroupId(request.pullerGroupId());
        setting.setPullerGroupName(puller.getName());
        setting.setStationGroupId(request.stationGroupId());
        setting.setStationGroupName(station == null ? null : station.getName());
        setting.setManagerFinishGroupId(request.managerFinishGroupId());
        setting.setManagerFinishGroupName(
                managerFinish == null ? null : managerFinish.getName());
        setting.setPullerFinishGroupId(request.pullerFinishGroupId());
        setting.setPullerFinishGroupName(pullerFinish == null ? null : pullerFinish.getName());
    }
}
