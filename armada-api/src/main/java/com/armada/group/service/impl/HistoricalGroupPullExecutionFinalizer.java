package com.armada.group.service.impl;

import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupAddStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 历史群拉人执行的持久化终态汇总器。 */
@Service
public class HistoricalGroupPullExecutionFinalizer {

    /** 执行聚合数据访问。 */
    private final HistoricalGroupPullExecutionMapper executionMapper;

    /** 成员终态数据访问。 */
    private final HistoricalGroupPullMemberMapper memberMapper;

    /**
     * 创建拉人终态汇总器。
     *
     * @param executionMapper 执行聚合数据访问
     * @param memberMapper    成员终态数据访问
     */
    public HistoricalGroupPullExecutionFinalizer(
            HistoricalGroupPullExecutionMapper executionMapper,
            HistoricalGroupPullMemberMapper memberMapper) {
        this.executionMapper = executionMapper;
        this.memberMapper = memberMapper;
    }

    /**
     * 从已持久化 ADD 结果计算执行终态并以单条条件更新完成执行。
     *
     * <p>联系人保存结果不影响拉人成功判定。执行已经被其它终态冻结时，条件更新不会覆盖。</p>
     *
     * @param executionId 执行 ID
     * @param failureStage 前置或 worker 失败阶段；正常完成时为空
     * @param errorCode    完整错误码的安全长度快照；正常完成时为空
     * @param errorMessage 完整错误信息的安全长度快照；正常完成时为空
     */
    @Transactional(rollbackFor = Exception.class)
    public void finish(
            Long executionId,
            String failureStage,
            String errorCode,
            String errorMessage) {
        List<HistoricalGroupPullMember> members = memberMapper.selectOrderedByExecutionId(executionId);
        int successCount = (int) members.stream()
                .filter(member -> member.getAddStatus() == HistoricalGroupAddStatus.SUCCESS.code())
                .count();
        int failureCount = members.size() - successCount;
        long now = System.currentTimeMillis();
        HistoricalGroupPullExecution terminal = new HistoricalGroupPullExecution();
        terminal.setId(executionId);
        terminal.setPullSuccessCount(successCount);
        terminal.setPullFailureCount(failureCount);
        terminal.setPullStatus(terminalStatus(successCount, failureCount).code());
        terminal.setFailureStage(failureStage);
        terminal.setErrorCode(errorCode);
        terminal.setErrorMessage(errorMessage);
        terminal.setFinishedAt(now);
        terminal.setUpdatedAt(now);
        executionMapper.finishIfRunning(terminal, HistoricalGroupPullStatus.RUNNING.code());
    }

    private static HistoricalGroupPullStatus terminalStatus(int successCount, int failureCount) {
        if (successCount > 0 && failureCount == 0) {
            return HistoricalGroupPullStatus.SUCCESS;
        }
        if (successCount > 0) {
            return HistoricalGroupPullStatus.PARTIAL_SUCCESS;
        }
        return HistoricalGroupPullStatus.FAILED;
    }
}
