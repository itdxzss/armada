package com.armada.task.service;

import com.armada.task.model.dto.PullTaskGroupMarketingSettingDTO;
import com.armada.task.model.vo.PullTaskGroupMarketingSettingVO;

/** 租户拉群营销全局设置服务。 */
public interface PullTaskGroupMarketingSettingService {

    /**
     * 查询当前租户设置，未配置时不创建默认行。
     *
     * @return 设置状态和值
     */
    PullTaskGroupMarketingSettingVO get();

    /**
     * 保存当前租户设置。
     *
     * @param request    三项设置值
     * @param operatorId 当前登录用户 ID
     * @return 保存后的设置
     */
    PullTaskGroupMarketingSettingVO save(
            PullTaskGroupMarketingSettingDTO request,
            long operatorId);
}
