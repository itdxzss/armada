package com.armada.marketing.converter;

import com.armada.marketing.model.dto.ScriptMarketingSaveDTO;
import com.armada.marketing.model.dto.ScriptDefinitionSaveDTO;
import com.armada.marketing.model.entity.ScriptMarketingDefinition;
import com.armada.marketing.model.entity.ScriptMarketingTask;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.model.entity.ScriptMarketingSendRecord;
import com.armada.marketing.model.vo.ScriptMarketingGroupVO;
import com.armada.marketing.model.vo.ScriptMarketingSendRecordVO;
import org.mapstruct.Mapper;

/** 配置和展示对象转换。 */
@Mapper(componentModel = "spring")
public interface ScriptMarketingConverter {
    /** 定义表单转持久化字段；租户、创建人与消息 JSON 由 Service 设置。 */
    ScriptMarketingDefinition toDefinition(ScriptDefinitionSaveDTO dto);
    /** 草稿表单转任务；状态与租户由 Service 填充。 */
    ScriptMarketingTask toTask(ScriptMarketingSaveDTO dto);
    /** 群进度展示。 */ ScriptMarketingGroupVO toGroupVO(ScriptMarketingGroup group);
    /** 单项结果展示。 */ ScriptMarketingSendRecordVO toRecordVO(ScriptMarketingSendRecord row);
}
