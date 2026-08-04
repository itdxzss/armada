package com.armada.task.service;

import com.armada.task.model.dto.PullTaskManagerJoinCallback;

/** 普通拉群管理员踩链接异步结果状态机。 */
public interface PullTaskManagerJoinResultService {

    /**
     * 幂等应用一条管理员踩链接结果并推进对应执行检查点。
     *
     * @param callback 已通过 Kafka 信封与关联字段校验的结果
     * @return 找到并接受对应命令时为 true；迟到或不匹配时为 false
     */
    boolean apply(PullTaskManagerJoinCallback callback);
}
