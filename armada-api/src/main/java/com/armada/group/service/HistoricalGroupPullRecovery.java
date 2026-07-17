package com.armada.group.service;

import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupContactStatus;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 服务启动时冻结历史群域遗留处理中状态，不恢复或重试协议动作。 */
@Service
public class HistoricalGroupPullRecovery {

    /** 安全日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(HistoricalGroupPullRecovery.class);

    /** 启动恢复统一错误码。 */
    private static final String ERROR_CODE = "SERVICE_INTERRUPTED";

    /** 启动恢复统一失败阶段。 */
    private static final String FAILURE_STAGE = "SERVICE_RECOVERY";

    /** 启动恢复完整错误说明。 */
    private static final String ERROR_MESSAGE =
            "服务重启中断一次性执行，处理中步骤已失败且不会自动重试";

    /** 执行聚合数据访问。 */
    private final HistoricalGroupPullExecutionMapper executionMapper;

    /** 成员明细数据访问。 */
    private final HistoricalGroupPullMemberMapper memberMapper;

    /**
     * 创建历史群启动恢复服务。
     *
     * @param executionMapper 执行聚合数据访问
     * @param memberMapper    成员明细数据访问
     */
    public HistoricalGroupPullRecovery(
            HistoricalGroupPullExecutionMapper executionMapper,
            HistoricalGroupPullMemberMapper memberMapper) {
        this.executionMapper = executionMapper;
        this.memberMapper = memberMapper;
    }

    /**
     * 应用就绪时跨租户冻结所有遗留 RUNNING/SENDING 状态。
     *
     * <p>必须先冻结成员明细，再调用 Task8 的执行恢复 SQL；否则执行先离开 RUNNING 后，
     * 成员 SQL 将无法识别所属遗留任务。重复调用不会重新排队，也不会覆盖已终态行。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(rollbackFor = Exception.class)
    public void recoverInterruptedExecutions() {
        long now = System.currentTimeMillis();
        HistoricalGroupPullMember failure = recoveryFailure(now);
        int pullingMembers = memberMapper.failStalePulling(
                failure,
                HistoricalGroupPullStatus.RUNNING.code(),
                HistoricalGroupContactStatus.PENDING.code(),
                HistoricalGroupContactStatus.FAILED.code());
        int sendingMembers = memberMapper.failStaleSending(
                HistoricalGroupMemberSendStatus.SENDING.code(),
                HistoricalGroupMemberSendStatus.FAILED.code(),
                ERROR_CODE,
                ERROR_MESSAGE,
                now);
        int executions = executionMapper.failStaleInProgress(
                HistoricalGroupPullStatus.RUNNING.code(),
                HistoricalGroupPullStatus.FAILED.code(),
                HistoricalGroupMarketingStatus.SENDING.code(),
                HistoricalGroupMarketingStatus.FAILED.code(),
                FAILURE_STAGE,
                ERROR_CODE,
                ERROR_MESSAGE,
                now);
        log.info("历史群启动恢复完成 executions={} pullingMembers={} sendingMembers={}",
                executions, pullingMembers, sendingMembers);
    }

    private static HistoricalGroupPullMember recoveryFailure(long now) {
        HistoricalGroupPullMember row = new HistoricalGroupPullMember();
        row.setContactErrorCode(ERROR_CODE);
        row.setContactErrorMessage(ERROR_MESSAGE);
        row.setAddErrorCode(ERROR_CODE);
        row.setAddErrorMessage(ERROR_MESSAGE);
        row.setUpdatedAt(now);
        return row;
    }
}
