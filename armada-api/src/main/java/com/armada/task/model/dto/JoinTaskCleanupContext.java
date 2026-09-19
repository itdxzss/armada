package com.armada.task.model.dto;

import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 固定清理名单与已完成游标；不是当前群成员事实的镜像。
 * @param originalAlreadyAbsent 建立名单时已确认原号不在群；旧记录缺省为 false，仍执行原有退群流程
 */
public record JoinTaskCleanupContext(List<GroupParticipantResult> targets, int completed,
        String newPhone, String originalPhone, boolean originalAlreadyAbsent) {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 当前目标成功后前进一位。 */
    public JoinTaskCleanupContext advance() {
        return new JoinTaskCleanupContext(targets, completed + 1, newPhone, originalPhone, originalAlreadyAbsent);
    }
    /** 固定名单持久化，不依赖调用进程内存恢复。 */
    public String toJson() {
        try { return JSON.writeValueAsString(this); }
        catch (java.io.IOException ex) { throw new IllegalStateException("清理名单序列化失败", ex); }
    }
    /** 读取既定名单，不能在重试时重新扩大清理范围。 */
    public static JoinTaskCleanupContext parse(String json) {
        try { return JSON.readValue(json, JoinTaskCleanupContext.class); }
        catch (java.io.IOException ex) { throw new IllegalStateException("清理名单解析失败", ex); }
    }
}
