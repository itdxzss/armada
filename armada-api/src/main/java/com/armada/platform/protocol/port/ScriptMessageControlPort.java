package com.armada.platform.protocol.port;

/** 剧本原命令控制，仅可恢复已确认未投递且由本端暂停的同一命令。 */
public interface ScriptMessageControlPort {
    /** 暂停尚未投递命令；发送权已提交时返回 false，继续跟踪原结果。 */
    boolean hold(String commandId);
    /** 将本端暂停的原命令恢复到队列，不插入新命令。 */
    boolean resume(String commandId);
    /** 到期/关闭取消未投递命令，并禁止发送中的失败重试；返回是否确认未投递。 */
    boolean expire(String commandId);
}
