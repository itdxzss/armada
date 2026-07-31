package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskGroupMarketingSetting;
import org.apache.ibatis.annotations.Mapper;

/** 租户拉群营销全局设置数据访问层。 */
@Mapper
public interface PullTaskGroupMarketingSettingMapper {

    /**
     * 查询当前租户设置。
     *
     * @return 设置行；尚未配置时返回 {@code null}
     */
    PullTaskGroupMarketingSetting selectCurrent();

    /**
     * 首次插入或覆盖当前租户的业务值和更新审计字段。
     *
     * @param setting 待保存设置
     * @return 数据库影响行数
     */
    int upsert(PullTaskGroupMarketingSetting setting);
}
