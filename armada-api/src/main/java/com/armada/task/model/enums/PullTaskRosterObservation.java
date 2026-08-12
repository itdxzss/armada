package com.armada.task.model.enums;

/** 逐成员结果对目标号码形成的最终观察。 */
public enum PullTaskRosterObservation {

    /** 名单查询成功且目标在群。 */
    PRESENT,
    /** 名单查询成功且目标不在群。 */
    ABSENT,
    /** 名单查询失败、跳过或缺少可用查询账号。 */
    UNAVAILABLE,
    /** 逐成员结果窗口结束仍无明确结论，不再发起名单查询。 */
    UNCONFIRMED
}
