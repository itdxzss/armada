package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskAuditEvent;
import org.apache.ibatis.annotations.Mapper;

/** 超链任务持久审计事件数据访问。 */
@Mapper
public interface HyperlinkTaskAuditEventMapper {

    /** 以租户与事件键幂等写入审计事实。 */
    int insertIdempotent(HyperlinkTaskAuditEvent event);

    /** 执行零行更新，确认审计表存在且当前连接具备写权限。 */
    int assertWritable();
}
