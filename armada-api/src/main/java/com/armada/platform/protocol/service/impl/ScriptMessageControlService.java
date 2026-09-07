package com.armada.platform.protocol.service.impl;

import com.armada.platform.protocol.mapper.ScriptMessageControlMapper;
import com.armada.platform.protocol.port.ScriptMessageControlPort;
import org.springframework.stereotype.Service;

/** 利用现有 outbox 发送权 CAS 控制剧本原命令，不重放已投递消息。 */
@Service
public class ScriptMessageControlService implements ScriptMessageControlPort {
    private final ScriptMessageControlMapper mapper;
    /** 注入原命令控制 SQL。 */
    public ScriptMessageControlService(ScriptMessageControlMapper mapper) { this.mapper = mapper; }
    /** 尚未提交发送权才允许暂停。 */
    @Override public boolean hold(String commandId) {
        return mapper.hold(commandId, System.currentTimeMillis()) == 1;
    }
    /** 恢复由本端确认暂停的同一行，既有 outbox scheduler 会投递。 */
    @Override public boolean resume(String commandId) {
        return mapper.resume(commandId, System.currentTimeMillis()) == 1;
    }
    /** 未投递取消；已提交的发送禁止失败重试，等待原结果。 */
    @Override public boolean expire(String commandId) {
        long now = System.currentTimeMillis();
        boolean canceled = mapper.expirePending(commandId, now) == 1;
        mapper.expireDispatching(commandId, now);
        return canceled;
    }
}
