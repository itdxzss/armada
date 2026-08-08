package com.armada.task.model.enums;

/**
 * 普通群链接创建页里，粘贴文本每一行的判定结果。
 *
 * <p>一行只落一个终态。只有 {@link #VALID} 进入随机匹配池。</p>
 */
public enum PullTaskStandardLinkLineStatus {

    /** 格式合法且未被其它任务占用，进入匹配池，真实可用性由管理员实际进群判定。 */
    VALID,

    /** 未提取到 22 位邀请码，或链接长度不足，不进入匹配池。 */
    INVALID_FORMAT,

    /** 本次粘贴内容里归一化后重复，保留首次出现的那一行，本行不进入匹配池。 */
    DUPLICATE,

    /** 该链接已被本租户其他运行中的任务占用，不进入匹配池。 */
    OCCUPIED
}
