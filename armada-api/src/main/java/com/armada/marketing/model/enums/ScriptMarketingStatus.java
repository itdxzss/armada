package com.armada.marketing.model.enums;

/** 剧本任务与发送状态码，分别存于各自状态列。 */
public final class ScriptMarketingStatus {
    private ScriptMarketingStatus() { }
    /** 未启动，可编辑。 */ public static final int DRAFT = 0;
    /** 运行或等待开始。 */ public static final int RUNNING = 1;
    /** 暂停，允许人工接管。 */ public static final int PAUSED = 2;
    /** 全部步骤结束等待。 */ public static final int FINISHED = 3;
    /** 主动关闭或到期。 */ public static final int CLOSED = 4;
    /** 已提交原命令，等待回调。 */ public static final int SENDING = 1;
    /** 明确成功。 */ public static final int SUCCESS = 2;
    /** 明确失败，不重试。 */ public static final int FAILED = 3;
    /** 超时且结果未知，不重发。 */ public static final int UNKNOWN = 4;
    /** 已确认原命令尚未投递，暂停持有。 */ public static final int HELD = 5;
    /** 独立消息来源。 */ public static final String SOURCE = "script_marketing";
}
