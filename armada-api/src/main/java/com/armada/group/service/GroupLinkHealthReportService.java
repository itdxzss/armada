package com.armada.group.service;

import com.armada.group.model.dto.GroupLinkHealthReportedEvent;
import java.util.Optional;

/**
 * 群链接健康检测回报落库服务。
 */
public interface GroupLinkHealthReportService {

    /**
     * 应用协议层 {@code group.health_reported} 回报事件，并返回租户内解析出的群入口。
     *
     * @param event 群链接健康检测回报事件
     * @return 已写入健康状态的群入口 ID；未匹配有效群时为空
     */
    Optional<Long> applyHealthReported(GroupLinkHealthReportedEvent event);
}
