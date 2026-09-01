package com.armada.platform.protocol.risk.mapper;

import com.armada.platform.protocol.risk.model.ProtocolRiskEvent;
import org.apache.ibatis.annotations.Mapper;

/** 协议风控追加事实数据访问。 */
@Mapper
public interface ProtocolRiskEventMapper {
    /** 同租户同事件只保存首次收到的事实，不覆盖历史。 */
    int insertIdempotent(ProtocolRiskEvent event);
}
