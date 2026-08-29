package com.armada.hyperlink.task.model.enums;

/** 超链任务人工生命周期动作。 */
public enum HyperlinkTaskAction {
    /** 启用未开始任务并进入准备或立即执行。 */
    START,
    /** 暂停运行任务，不再生成新协议命令。 */
    PAUSE,
    /** 从原进度继续已暂停任务。 */
    RESUME,
    /** 终止任务且不可恢复。 */
    STOP
}
