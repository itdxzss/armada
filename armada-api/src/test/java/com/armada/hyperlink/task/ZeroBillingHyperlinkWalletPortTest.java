package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.hyperlink.task.model.vo.HyperlinkRecipientCountryCount;
import com.armada.hyperlink.task.port.ZeroBillingHyperlinkWalletPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 第一套环境零计费提供方的金额边界与幂等结果测试。 */
class ZeroBillingHyperlinkWalletPortTest {

    private final ZeroBillingHyperlinkWalletPort wallet =
            new ZeroBillingHyperlinkWalletPort();

    @Test
    void quoteKeepsRecipientBreakdownButPricesEveryAmountAtZero() {
        var quote = wallet.quote(7L, 10, List.of(
                new HyperlinkRecipientCountryCount("BR", 3),
                new HyperlinkRecipientCountryCount("US", 2)));

        assertThat(quote.provider()).isEqualTo("ZERO_TEST");
        assertThat(quote.pricingMode()).isEqualTo("NORMAL");
        assertThat(quote.priceCode()).isEqualTo("ZERO_TEST_V1");
        assertThat(quote.estimatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quote.accountBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quote.giftBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quote.breakdown())
                .extracting(row -> row.countryIso2() + ":" + row.recipientCount()
                        + ":" + row.amount().toPlainString())
                .containsExactly("BR:3:0", "US:2:0");
    }

    @Test
    void zeroAmountLifecycleReturnsStableIdempotentResults() {
        String operationKey = "hl:reserve:stable-operation";

        var first = wallet.reserve(7L, 11L, operationKey, "USD", BigDecimal.ZERO);
        var replay = wallet.reserve(7L, 11L, operationKey, "USD", BigDecimal.ZERO);

        assertThat(first.externalReservationNo()).isEqualTo(operationKey);
        assertThat(replay).isEqualTo(first);
        assertThat(wallet.adjust(7L, 11L, "hl:adjust", operationKey,
                "USD", BigDecimal.ZERO).reservedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.settle(7L, 11L, "hl:settle", operationKey,
                "USD", BigDecimal.ZERO, 5).settledSendCount()).isEqualTo(5);
        assertThat(wallet.release(7L, 11L, "hl:release", operationKey,
                "USD", BigDecimal.ZERO).releasedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void rejectsAnyNonZeroFinancialOperation() {
        assertThatThrownBy(() -> wallet.reserve(
                7L, 11L, "hl:reserve", "USD", BigDecimal.ONE))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE.code()));
    }
}
