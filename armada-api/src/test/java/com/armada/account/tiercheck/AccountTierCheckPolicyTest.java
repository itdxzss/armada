package com.armada.account.tiercheck;

import com.armada.account.service.AccountTierCheckPolicy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AccountTierCheckPolicyTest {
    @Test void classifiesOnlyTheInferredChainAndKeepsEmptyDistinctFromLostTier() {
        assertThat(AccountTierCheckPolicy.change(null, "TIER_1", true)).isZero();
        assertThat(AccountTierCheckPolicy.change("TIER_1", "TIER_1", true)).isEqualTo(1);
        assertThat(AccountTierCheckPolicy.change("SUSPICIOUS", "TIER_3", true)).isEqualTo(2);
        assertThat(AccountTierCheckPolicy.change("TIER_2", "TIER_0", true)).isEqualTo(3);
        assertThat(AccountTierCheckPolicy.change("UNTIERED", "TIER_0", true)).isEqualTo(4);
        assertThat(AccountTierCheckPolicy.change("TIER_1", "UNTIERED", true)).isEqualTo(4);
        assertThat(AccountTierCheckPolicy.change("FUTURE_TIER", "TIER_1", true)).isEqualTo(4);
        assertThat(AccountTierCheckPolicy.change("TIER_1", null, false)).isEqualTo(5);
        assertThat(AccountTierCheckPolicy.change(null, null, false)).isZero();
    }
}
