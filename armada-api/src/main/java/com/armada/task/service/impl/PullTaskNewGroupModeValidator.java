package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.dto.PullTaskStandardGroupSettingDTO;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;

/**
 * 新群模式创建入参的专属校验。
 *
 * <p>只处理新群模式特有的字段，群链接模式的既有校验不受影响：模式为空或为
 * {@code PASTED_LINK} 时整体跳过，存量前端不传新字段也能照常提交。</p>
 *
 * <p>无状态纯逻辑，不注册为 Spring Bean——注册反而要给唯一的构造点加参数，
 * 牵动既有测试的装配代码。</p>
 */
public final class PullTaskNewGroupModeValidator {

    private static final int GROUP_NAME_MAX_LENGTH = 100;
    private static final int GROUP_DESCRIPTION_MAX_LENGTH = 1024;
    private static final int PULL_COUNT_MIN = 1;
    private static final int PULL_COUNT_MAX = 3;
    private static final int INTERVAL_MIN_SECONDS = 10;
    private static final int INTERVAL_MAX_SECONDS = 15;

    /** 工具类不实例化；测试为可读性保留 new，故构造器不设为 private。 */
    PullTaskNewGroupModeValidator() {
    }

    /**
     * 校验建群相关入参。
     *
     * @param request 整单提交入参
     */
    public void validate(PullTaskStandardCreateDTO request) {
        validateRequest(request);
    }

    /**
     * 校验建群相关入参。
     *
     * @param request 整单提交入参
     */
    public static void validateRequest(PullTaskStandardCreateDTO request) {
        if (request != null && Boolean.TRUE.equals(request.creatorDeleteAfterTakeover())
                && (!PullTaskCreationMode.fromNullable(request.creationMode()).isNewGroup()
                || request.managerGroupId() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "管理员接管后注销仅支持新群模式，且必须选择接管管理分组");
        }
        if (request == null
                || !PullTaskCreationMode.fromNullable(request.creationMode()).isNewGroup()) {
            return;
        }
        if (request.creatorGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模式必须选择建群人账号分组");
        }
        int initialStationCount = initialStationCount(request);
        if (initialStationCount < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "建群初始站台数量不能为负数");
        }
        if (initialStationCount > 0 && request.stationGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "建群初始站台数量大于 0 时必须选择站台分组");
        }
        validateProfile(request.groupSetting(), request.creationMode().isSimplifiedNewGroup());
        validatePullParameters(request);
    }

    private static void validateProfile(PullTaskStandardGroupSettingDTO setting, boolean simple) {
        if (setting == null || !Boolean.TRUE.equals(setting.enabled())
                || setting.settingTiming() != PullTaskGroupSettingTiming.BEFORE_PULL) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "新群模式必须开启群信息设置，并在拉人前设置成功");
        }
        if (Boolean.TRUE.equals(setting.useMaterialFileNameAsGroupName())) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模式必须填写群名称，不能使用料子文件名");
        }
        if (setting.groupName() == null || setting.groupName().trim().isEmpty()
                || setting.groupName().trim().length() > GROUP_NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "新群模式群名称长度需在 1-" + GROUP_NAME_MAX_LENGTH + " 字符之间");
        }
        if ((!simple && (setting.groupDescription() == null || setting.groupDescription().trim().isEmpty()))
                || setting.groupDescription() != null
                && setting.groupDescription().trim().length() > GROUP_DESCRIPTION_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "新群模式群描述长度需在 1-" + GROUP_DESCRIPTION_MAX_LENGTH + " 字符之间");
        }
    }

    private static void validatePullParameters(PullTaskStandardCreateDTO request) {
        if (request.earlyPullCallCount() == null || request.earlyPullCallCount() != 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模式从首次调用起使用人数范围，前期固定次数必须为 0");
        }
        if (request.pullCountMin() == null || request.pullCountMax() == null
                || request.pullCountMin() < PULL_COUNT_MIN || request.pullCountMax() > PULL_COUNT_MAX
                || request.pullCountMin() > request.pullCountMax()) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模式单次拉人数必须在 1-3 人范围内");
        }
        Integer minimum = request.pullIntervalSeconds();
        Integer maximum = request.pullIntervalMaxSeconds() == null
                ? minimum : request.pullIntervalMaxSeconds();
        if (minimum == null || minimum < INTERVAL_MIN_SECONDS
                || maximum > INTERVAL_MAX_SECONDS || maximum < minimum) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模式拉人间隔必须在 10-15 秒范围内");
        }
    }

    /**
     * 本任务对站台分组的真实需求人数。
     *
     * <p>取两笔需求的较大值而不是相加：建群时进群的站台会落成 {@code IN_GROUP} 的站台角色行，
     * 而拉人调用的选号逻辑会把本执行行已有的站台角色行全部排除，因此这批站台不会被重复占用。
     * 相加会凭空抬高门槛，把本可执行的配置拦在创建阶段。</p>
     *
     * @param initialStationCount 建群时作为初始成员加入的站台数量
     * @param stationCountPerCall 每一次拉人调用叠加的站台数量
     * @return 站台分组至少需要提供的可用账号数
     */
    public static int stationDemand(int initialStationCount, int stationCountPerCall) {
        return Math.max(initialStationCount, stationCountPerCall);
    }

    /**
     * 读取初始站台数量，空值按 0 处理。
     *
     * @param request 整单提交入参
     * @return 初始站台数量
     */
    public static int initialStationCount(PullTaskStandardCreateDTO request) {
        return request == null || request.initialStationCount() == null
                ? 0 : request.initialStationCount();
    }
}
