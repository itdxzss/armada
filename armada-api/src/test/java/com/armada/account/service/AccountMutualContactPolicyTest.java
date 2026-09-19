package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

/** 跨组规模和单账号间隔的业务边界。 */
class AccountMutualContactPolicyTest {
    @Test
    void countsBothDirectionsAndAcceptsZeroInterval() {
        assertThat(AccountMutualContactPolicy.operationCount(2, 3)).isEqualTo(12);
        assertThat(AccountMutualContactPolicy.nextAt(1000, 0)).isEqualTo(1000);
        assertThat(AccountMutualContactPolicy.nextAt(1000, 3)).isEqualTo(4000);
    }
    @Test
    void rejectsEmptyOrUnboundedBatchesAndInvalidInterval() {
        assertThatThrownBy(() -> AccountMutualContactPolicy.operationCount(0, 3)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AccountMutualContactPolicy.operationCount(1000, 1000))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AccountMutualContactPolicy.nextAt(1000, -1)).isInstanceOf(BusinessException.class);
    }
}
