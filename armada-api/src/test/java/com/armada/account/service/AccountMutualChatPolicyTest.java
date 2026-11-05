package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class AccountMutualChatPolicyTest {
    @Test void validatesCrossProductAndInterval() {
        assertThat(AccountMutualChatPolicy.operationCount(2, 3)).isEqualTo(12);
        assertThat(AccountMutualChatPolicy.operationCount(50, 50)).isEqualTo(5000);
        assertThatThrownBy(() -> AccountMutualChatPolicy.operationCount(51, 50)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AccountMutualChatPolicy.operationCount(0, 3)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AccountMutualChatPolicy.nextAt(0, 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AccountMutualChatPolicy.nextAt(0, 3601)).isInstanceOf(BusinessException.class);
        assertThat(AccountMutualChatPolicy.nextAt(1000, 10)).isEqualTo(11000);
        assertThat(AccountMutualChatPolicy.nextAt(1000, 3600)).isEqualTo(3601000);
    }
    @Test void trimsAndBoundsBothTexts() {
        assertThat(AccountMutualChatPolicy.text("  hello  ")).isEqualTo("hello");
        assertThat(AccountMutualChatPolicy.text("a".repeat(1000))).hasSize(1000);
        assertThatThrownBy(() -> AccountMutualChatPolicy.text(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AccountMutualChatPolicy.text(" \n ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AccountMutualChatPolicy.text("a".repeat(1001))).isInstanceOf(BusinessException.class);
    }
}
