package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupAddStatus;
import com.armada.group.model.enums.HistoricalGroupContactStatus;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 历史群一次性任务启动恢复真库测试。 */
class HistoricalGroupPullRecoveryDbTest extends DbTestBase {

    @Autowired
    private HistoricalGroupPullExecutionMapper executionMapper;

    @Autowired
    private HistoricalGroupPullMemberMapper memberMapper;

    @Autowired
    private HistoricalGroupPullRecovery recovery;

    @Test
    void startupRecoveryFailsPullAndSendingRowsWithoutRequeueingThem() {
        long now = System.currentTimeMillis();
        HistoricalGroupPullExecution execution = runningExecution(now);
        executionMapper.insert(execution);
        HistoricalGroupPullMember member = inProgressMember(execution.getId(), now);
        memberMapper.batchInsert(List.of(member));

        recovery.recoverInterruptedExecutions();

        HistoricalGroupPullExecution recovered =
                executionMapper.selectByTenantAndId(TEST_TENANT_ID, execution.getId());
        HistoricalGroupPullMember recoveredMember =
                memberMapper.selectOrderedByExecutionId(execution.getId()).get(0);
        assertThat(recovered.getPullStatus()).isEqualTo(HistoricalGroupPullStatus.FAILED.code());
        assertThat(recovered.getMarketingStatus()).isEqualTo(HistoricalGroupMarketingStatus.FAILED.code());
        assertThat(recovered.getFailureStage()).isEqualTo("SERVICE_RECOVERY");
        assertThat(recovered.getErrorCode()).isEqualTo("SERVICE_INTERRUPTED");
        assertThat(recovered.getFinishedAt()).isNotNull();
        assertThat(recoveredMember.getContactStatus()).isEqualTo(HistoricalGroupContactStatus.FAILED.code());
        assertThat(recoveredMember.getAddStatus()).isEqualTo(HistoricalGroupAddStatus.FAILED.code());
        assertThat(recoveredMember.getSendStatus()).isEqualTo(HistoricalGroupMemberSendStatus.FAILED.code());
        assertThat(recoveredMember.getContactErrorCode()).isEqualTo("SERVICE_INTERRUPTED");
        assertThat(recoveredMember.getAddErrorCode()).isEqualTo("SERVICE_INTERRUPTED");
        assertThat(recoveredMember.getSendErrorCode()).isEqualTo("SERVICE_INTERRUPTED");

        recovery.recoverInterruptedExecutions();

        HistoricalGroupPullExecution secondPass =
                executionMapper.selectByTenantAndId(TEST_TENANT_ID, execution.getId());
        assertThat(secondPass.getUpdatedAt()).isEqualTo(recovered.getUpdatedAt());
        assertThat(secondPass.getPullStatus()).isEqualTo(HistoricalGroupPullStatus.FAILED.code());
    }

    private static HistoricalGroupPullExecution runningExecution(long now) {
        HistoricalGroupPullExecution row = new HistoricalGroupPullExecution();
        row.setIdempotencyKey("recovery-" + now);
        row.setOperationAccountId(1001L);
        row.setGroupJid("120363recovery@g.us");
        row.setInviteLink("https://chat.whatsapp.com/recovery");
        row.setPullerAccountGroupId(2001L);
        row.setSingleAddCount(5);
        row.setPullStatus(HistoricalGroupPullStatus.RUNNING.code());
        row.setMarketingStatus(HistoricalGroupMarketingStatus.SENDING.code());
        row.setStartedAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static HistoricalGroupPullMember inProgressMember(long executionId, long now) {
        HistoricalGroupPullMember row = new HistoricalGroupPullMember();
        row.setExecutionId(executionId);
        row.setLineNo(1);
        row.setPhone("8613900000901");
        row.setMaterialType(HistoricalGroupMaterialType.MARKETING.code());
        row.setContactStatus(HistoricalGroupContactStatus.PENDING.code());
        row.setAddStatus(HistoricalGroupAddStatus.PENDING.code());
        row.setSendStatus(HistoricalGroupMemberSendStatus.SENDING.code());
        row.setSendCommandId("recovery-command-" + now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }
}
