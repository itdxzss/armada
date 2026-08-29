package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.hyperlink.task.port.UnavailableHyperlinkTaskAuditPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.junit.jupiter.api.Test;

/** 没有真实审计适配器时，超链任务写操作必须失败关闭。 */
class HyperlinkTaskAuditPortTest {

    @Test
    void unavailablePortRejectsBeforeAnyAuditedMutation() {
        assertThatThrownBy(new UnavailableHyperlinkTaskAuditPort()::requireAvailable)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.HYPERLINK_AUDIT_UNAVAILABLE.code()));
    }
}
