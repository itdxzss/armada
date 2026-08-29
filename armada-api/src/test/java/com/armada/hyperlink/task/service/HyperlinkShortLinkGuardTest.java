package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 公网短链基址不能携带可混淆 authority、查询或片段。 */
class HyperlinkShortLinkGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:secret@links.example.test",
            "https://links.example.test/root?tenant=7",
            "https://links.example.test/root#preview"
    })
    void rejectsCredentialsQueryAndFragment(String configured) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HyperlinkShortLinkGuard(configured));
    }

    @Test
    void acceptsHttpBasePathAndBuildsTheFrozenEndpoint() {
        HyperlinkShortLinkGuard guard =
                new HyperlinkShortLinkGuard("https://links.example.test/root/");

        assertThat(guard.publicUrl("AbCdEf0123_-xyZ9"))
                .isEqualTo("https://links.example.test/root/api/public/hl/AbCdEf0123_-xyZ9");
    }

    @Test
    void missingConfigurationFailsWithStableDispatchGuardCode() {
        HyperlinkShortLinkGuard guard = new HyperlinkShortLinkGuard("");

        assertThatThrownBy(() -> guard.requireConfigured(true))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(
                                ErrorCode.HYPERLINK_DISPATCH_GUARD_UNAVAILABLE.code()));
    }
}
