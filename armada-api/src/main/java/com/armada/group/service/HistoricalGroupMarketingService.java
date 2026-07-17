package com.armada.group.service;

import com.armada.group.model.dto.HistoricalGroupMarketingSendDTO;
import com.armada.group.model.vo.HistoricalGroupPullExecutionVO;

/** 历史群执行中全部营销账号的一次性发送服务。 */
public interface HistoricalGroupMarketingService {

    /**
     * 使用执行固定的操作账号和目标群重新校验邀请链接，并为全部营销成员各下发一次模板消息。
     *
     * @param executionId 历史群执行 ID
     * @param request      当前租户模板选择
     * @return 启动后或重复请求时的当前执行状态
     */
    HistoricalGroupPullExecutionVO send(Long executionId, HistoricalGroupMarketingSendDTO request);
}
