package com.armada.task.model.enums;

import org.junit.jupiter.api.Test;

import static com.armada.task.model.enums.JoinTaskFailureReason.labelOf;
import static org.assertj.core.api.Assertions.assertThat;

class JoinTaskFailureReasonTest {

    @Test
    void labelOfReturnsExplicitLabelForAccountReachoutRestricted() {
        assertThat(JoinTaskFailureReason.labelOf("ACCOUNT_REACHOUT_RESTRICTED"))
                .isEqualTo("账号触达受限，无法进群");
    }

    @Test
    void labelOfMapsNewCanonicalJoinFailureCodes() {
        assertThat(labelOf("INVALID_GROUP_LINK")).isEqualTo("群邀请链接无效");
        assertThat(labelOf("GROUP_JOIN_REJECTED")).isEqualTo("协议拒绝进群");
        assertThat(labelOf("JOIN_RESULT_UNCONFIRMED")).isEqualTo("进群结果未确认");
        assertThat(labelOf("ANDROID_RESPONSE_UNRECOGNIZED")).isEqualTo("Android 协议响应无法识别");
        assertThat(labelOf("UNSUPPORTED_BACKEND")).isEqualTo("账号协议类型暂不支持");
        assertThat(labelOf("BAD_REQUEST")).isEqualTo("进群失败，请检查群链接或稍后重试");
        assertThat(labelOf("bad-request")).isEqualTo("进群失败，请检查群链接或稍后重试");
    }
}
