package com.armada.account.contact.model;

/** 通讯录同步的触发来源。 */
public enum ContactSyncSource {

    /** 账号上线状态事件触发。 */
    ONLINE_EVENT,
    /** 通讯录任务启动时按 TTL 触发。 */
    TASK_START,
    /** 运营手动触发。 */
    MANUAL
}
