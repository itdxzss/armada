package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;

/**
 * 账号当前群列表同步协议命令请求。
 *
 * <p>该命令由 Armada 定时巡检或营销导出触发：Web 账号发往 master，Android
 * 账号发往生命周期 topic。空 {@code groupJids} 保持全账号群同步，非空列表只查询
 * 任务指定群。payload 只携带账号引用与群 JID，不包含凭据、代理密码等敏感数据。</p>
 *
 * @param tenantId          账号所属租户 ID,用于结果事件回写时恢复租户上下文
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议层账号句柄,也是 master owner 路由 key
 * @param protocolBackend   协议后端，用于选择 Web master 或 Android 生命周期 topic
 * @param phone             Android 进程内查询 WaApp 所需号码；Web 可忽略
 * @param groupJids         需要定向读取的群 JID；空列表表示账号全部参与群
 * @param source            命令来源,用于排查和审计
 */
public record ProtocolAccountGroupSyncCommandRequest(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        ProtocolBackend protocolBackend,
        String phone,
        List<String> groupJids,
        String source
) {
}
