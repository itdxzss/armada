package com.armada.task.model.enums;

/**
 * 普通群链接创建页里，粘贴文本每一行的判定结果。
 *
 * <p>一行只落一个终态。只有 {@link #VALID} 与 {@link #PROBE_INCOMPLETE} 进入随机匹配池。</p>
 */
public enum PullTaskStandardLinkLineStatus {

    /** 格式合法且公开邀请页识别出群名或真实头像，进入匹配池。 */
    VALID,

    /** 未提取到 22 位邀请码，或链接长度不足，不进入匹配池。 */
    INVALID_FORMAT,

    /** 本次粘贴内容里归一化后重复，保留首次出现的那一行，本行不进入匹配池。 */
    DUPLICATE,

    /** 公开邀请页可访问但只有 WhatsApp 默认资料，判定为链接已失效，不进入匹配池。 */
    LINK_EXPIRED,

    /** 抓取超时或网络错误，无法判定有效性；仍进入匹配池，由启动时重新校验兜底。 */
    PROBE_INCOMPLETE,

    /** 该链接已被本租户其他运行中的任务占用，不进入匹配池。 */
    OCCUPIED
}
