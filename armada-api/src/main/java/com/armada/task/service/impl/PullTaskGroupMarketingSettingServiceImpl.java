package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskGroupMarketingSettingMapper;
import com.armada.task.model.dto.PullTaskGroupMarketingSettingDTO;
import com.armada.task.model.entity.PullTaskGroupMarketingSetting;
import com.armada.task.model.vo.PullTaskGroupMarketingSettingVO;
import com.armada.task.service.PullTaskGroupMarketingSettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户级拉群营销全局设置业务实现。
 *
 * <p>设置按当前租户隔离，每个租户最多保存一条记录。首次未配置时返回明确的未配置状态和空业务值，
 * 不创建默认记录；保存设置只更新租户级配置，不回写已经创建的拉群营销任务。</p>
 */
@Service
public class PullTaskGroupMarketingSettingServiceImpl
        implements PullTaskGroupMarketingSettingService {

    /** 三项设置未完整填写或超出允许范围时返回给运营端的校验提示。 */
    private static final String VALIDATION_MESSAGE =
            "静默和封控时间不能为负数，单群营销账号上限必须大于0";

    /** 租户拉群营销全局设置持久化入口，租户条件由 MyBatis 租户拦截器注入。 */
    private final PullTaskGroupMarketingSettingMapper mapper;

    /**
     * 装配租户拉群营销全局设置服务。
     *
     * @param mapper 租户拉群营销全局设置 Mapper
     */
    public PullTaskGroupMarketingSettingServiceImpl(
            PullTaskGroupMarketingSettingMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查询当前租户生效的拉群营销全局设置。
     *
     * <p>数据库没有当前租户记录时返回 {@code configured=false}，三个业务值保持为空，
     * 由调用方阻止拉群营销任务创建，不使用代码默认值兜底。</p>
     *
     * @return 当前租户的配置状态和三项业务值
     */
    @Override
    public PullTaskGroupMarketingSettingVO get() {
        PullTaskGroupMarketingSetting setting = mapper.selectCurrent();
        if (setting == null) {
            return new PullTaskGroupMarketingSettingVO(false, null, null, null);
        }
        return toVO(setting);
    }

    /**
     * 校验并保存当前租户的拉群营销全局设置。
     *
     * <p>三个字段必须一次性完整提交。保存通过租户唯一键原子新增或更新，并保留首次创建审计字段；
     * 本事务不修改历史拉群任务及其配置快照。</p>
     *
     * @param request 三项全局设置，静默和封控时间允许为 0，单群营销账号上限必须为正整数
     * @param operatorId 当前登录用户 ID，必须来自可信鉴权上下文
     * @return 保存后当前租户生效的设置
     * @throws BusinessException 当请求为空、字段缺失或字段值超出允许范围时抛出
     */
    @Override
    @Transactional
    public PullTaskGroupMarketingSettingVO save(
            PullTaskGroupMarketingSettingDTO request,
            long operatorId) {
        validate(request);
        long now = System.currentTimeMillis();
        PullTaskGroupMarketingSetting setting = new PullTaskGroupMarketingSetting();
        setting.setMarketingSilenceMinutes(request.marketingSilenceMinutes());
        setting.setGroupLockdownMinutes(request.groupLockdownMinutes());
        setting.setMaxMarketingAccountsPerGroup(request.maxMarketingAccountsPerGroup());
        setting.setCreatedBy(operatorId);
        setting.setUpdatedBy(operatorId);
        setting.setCreatedAt(now);
        setting.setUpdatedAt(now);
        mapper.upsert(setting);
        return toVO(setting);
    }

    /**
     * 校验三项设置是否完整且满足业务下限。
     *
     * @param request 待保存的租户全局设置
     * @throws BusinessException 当任一字段缺失、时间为负数或账号上限小于 1 时抛出
     */
    private static void validate(PullTaskGroupMarketingSettingDTO request) {
        if (request == null
                || request.marketingSilenceMinutes() == null
                || request.groupLockdownMinutes() == null
                || request.maxMarketingAccountsPerGroup() == null
                || request.marketingSilenceMinutes() < 0
                || request.groupLockdownMinutes() < 0
                || request.maxMarketingAccountsPerGroup() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, VALIDATION_MESSAGE);
        }
    }

    /**
     * 将已持久化或即将持久化的设置转换为已配置响应。
     *
     * @param setting 三项业务值均已通过校验的设置实体
     * @return 标记为已配置的设置响应
     */
    private static PullTaskGroupMarketingSettingVO toVO(
            PullTaskGroupMarketingSetting setting) {
        return new PullTaskGroupMarketingSettingVO(
                true,
                setting.getMarketingSilenceMinutes(),
                setting.getGroupLockdownMinutes(),
                setting.getMaxMarketingAccountsPerGroup());
    }
}
