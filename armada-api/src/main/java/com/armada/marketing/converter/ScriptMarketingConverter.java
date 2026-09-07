package com.armada.marketing.converter;

import com.armada.marketing.model.dto.ScriptMarketingSaveDTO;
import com.armada.marketing.model.entity.ScriptMarketingTask;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.model.entity.ScriptMarketingSendRecord;
import com.armada.marketing.model.vo.ScriptMarketingGroupVO;
import com.armada.marketing.model.vo.ScriptMarketingSendRecordVO;
import org.mapstruct.Mapper;

/** 配置和展示对象转换。 */
@Mapper(componentModel = "spring")
public interface ScriptMarketingConverter {
    /** 草稿表单转任务；状态与租户由 Service 填充。 */
    ScriptMarketingTask toTask(ScriptMarketingSaveDTO dto);
    /** 群进度展示。 */ ScriptMarketingGroupVO toGroupVO(ScriptMarketingGroup group);
    /** 单项结果展示。 */ ScriptMarketingSendRecordVO toRecordVO(ScriptMarketingSendRecord row);
}
