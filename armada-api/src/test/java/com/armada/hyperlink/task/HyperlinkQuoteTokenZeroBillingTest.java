package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.hyperlink.task.model.vo.HyperlinkTaskQuoteVO;
import com.armada.hyperlink.task.service.HyperlinkQuoteTokenService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 零计费环境未配置外部密钥时的进程内报价签名测试。 */
class HyperlinkQuoteTokenZeroBillingTest {

    @Test
    void zeroBillingGeneratesEphemeralSigningKeyWhenExternalKeyIsAbsent() {
        HyperlinkQuoteTokenService service = new HyperlinkQuoteTokenService(
                new ObjectMapper(), "", "ZERO_TEST");
        HyperlinkQuoteTokenService.QuoteClaims claims = claims();

        String token = service.sign(claims);

        assertThat(service.verify(token, 7L, 8L, "CREATE", null, null, 1000L))
                .isEqualTo(claims);
    }

    @Test
    void regularModeStillFailsClosedWithoutSigningKey() {
        HyperlinkQuoteTokenService service = new HyperlinkQuoteTokenService(
                new ObjectMapper(), "", "UNAVAILABLE");

        assertThatThrownBy(() -> service.sign(claims()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE.code()));
    }

    private HyperlinkQuoteTokenService.QuoteClaims claims() {
        HyperlinkTaskQuoteVO quote = new HyperlinkTaskQuoteVO(
                "", 2000L, 21L, 1, "测试数据包", 3,
                1, 1, "NORMAL", "ZERO_TEST_V1", "USD", BigDecimal.ZERO,
                List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return new HyperlinkQuoteTokenService.QuoteClaims(
                "quote-1", 7L, 8L, "CREATE", null, null,
                21L, 1, 99L, "instant", 1, 1, "ZERO_TEST", 2000L, quote);
    }
}
