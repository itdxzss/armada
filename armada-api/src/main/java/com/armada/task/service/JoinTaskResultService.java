package com.armada.task.service;

import com.armada.task.model.dto.JoinTaskResultReportedEvent;
import com.armada.task.model.dto.JoinTaskDeadCommandCandidate;

/**
 * 进群异步结果状态机。
 *
 * <p>无论结果来自 Web、Android，还是 Armada outbox 自身的传输失败，均由本服务决定当前尝试重试、
 * 进入终态或放行同账号下一行。Kafka 消费者和调度器不得各自维护另一套重试规则。</p>
 */
public interface JoinTaskResultService {

    /**
     * 幂等应用协议层进群结果。
     *
     * @param event 已通过 Kafka 信封校验的统一进群结果
     * @throws IllegalArgumentException 业务关联字段缺失或结果码不受支持时抛出
     */
    void apply(JoinTaskResultReportedEvent event);

    /**
     * 把当前仍匹配的 outbox DEAD 尝试按可重试传输失败应用。
     *
     * @param candidate DEAD 命令与任务明细的关联字段
     * @throws IllegalArgumentException 关联字段不完整时抛出
     */
    void applyTransportFailure(JoinTaskDeadCommandCandidate candidate);
}
