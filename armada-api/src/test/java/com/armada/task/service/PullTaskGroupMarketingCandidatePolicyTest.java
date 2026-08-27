package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.model.enums.PullTaskGroupCandidateStatus;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateRow;
import org.junit.jupiter.api.Test;

/** 拉群营销候选群组选择规则测试。 */
class PullTaskGroupMarketingCandidatePolicyTest {

    @Test
    void allowsOrdinaryOfflineAdminToEnterWaitingResourceState() {
        PullTaskGroupMarketingCandidateRow row = healthyRow();
        row.setOnlineAccountCount(0);

        PullTaskGroupMarketingCandidatePolicy.Decision decision =
                PullTaskGroupMarketingCandidatePolicy.evaluate(row, 7L, null, true);

        assertThat(decision.status()).isEqualTo(PullTaskGroupCandidateStatus.WAITING_ACCOUNT_ONLINE);
        assertThat(decision.selectable()).isTrue();
        assertThat(decision.disabledReason()).contains("等待恢复在线");
    }

    @Test
    void blocksMemberOnlyInvalidAccountAndUnhealthyGroup() {
        PullTaskGroupMarketingCandidateRow member = healthyRow();
        member.setAdminRelationCount(0);
        assertThat(PullTaskGroupMarketingCandidatePolicy.evaluate(member, 7L, null, true).status())
                .isEqualTo(PullTaskGroupCandidateStatus.NO_ADMIN_PERMISSION);

        PullTaskGroupMarketingCandidateRow invalidAccount = healthyRow();
        invalidAccount.setEligibleAccountCount(0);
        assertThat(PullTaskGroupMarketingCandidatePolicy.evaluate(
                invalidAccount, 7L, null, true).status())
                .isEqualTo(PullTaskGroupCandidateStatus.NO_ELIGIBLE_ACCOUNT);

        PullTaskGroupMarketingCandidateRow banned = healthyRow();
        banned.setBanned(true);
        assertThat(PullTaskGroupMarketingCandidatePolicy.evaluate(banned, 7L, null, true).status())
                .isEqualTo(PullTaskGroupCandidateStatus.GROUP_BANNED);
    }

    @Test
    void distinguishesCurrentPoolFromOtherOccupancy() {
        PullTaskGroupMarketingCandidateRow row = healthyRow();
        row.setOccupancyType("WAITING");
        row.setReservationToken("own-token");
        row.setOccupiedBy(7L);

        PullTaskGroupMarketingCandidatePolicy.Decision current =
                PullTaskGroupMarketingCandidatePolicy.evaluate(row, 7L, "own-token", true);
        assertThat(current.inCurrentWaitingPool()).isTrue();
        assertThat(current.selectable()).isFalse();
        assertThat(current.status()).isEqualTo(PullTaskGroupCandidateStatus.NORMAL);

        PullTaskGroupMarketingCandidatePolicy.Decision other =
                PullTaskGroupMarketingCandidatePolicy.evaluate(row, 8L, "other-token", true);
        assertThat(other.status()).isEqualTo(PullTaskGroupCandidateStatus.OCCUPIED);
        assertThat(other.selectable()).isFalse();
    }

    private static PullTaskGroupMarketingCandidateRow healthyRow() {
        PullTaskGroupMarketingCandidateRow row = new PullTaskGroupMarketingCandidateRow();
        row.setGroupJid("120363001@g.us");
        row.setAdminRelationCount(1);
        row.setEligibleAccountCount(1);
        row.setOnlineAccountCount(1);
        row.setHealthStatus(1);
        row.setBanned(false);
        return row;
    }
}
