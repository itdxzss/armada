package com.armada.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DataScopeContextTest {

    @AfterEach
    void clearContext() {
        DataScopeContext.clear();
    }

    @Test
    void nestedScopesRestorePreviousValueAndClearOuterValue() {
        DataScope self = DataScope.self(7L);
        DataScope all = DataScope.all(8L);

        assertThat(DataScopeContext.current()).isEmpty();
        try (DataScopeContext.Scope outer = DataScopeContext.open(self)) {
            assertThat(DataScopeContext.requireCurrent()).isEqualTo(self);
            try (DataScopeContext.Scope inner = DataScopeContext.open(all)) {
                assertThat(DataScopeContext.requireCurrent()).isEqualTo(all);
            }
            assertThat(DataScopeContext.requireCurrent()).isEqualTo(self);
            outer.close();
            outer.close();
        }
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @Test
    void missingScopeFailsClosed() {
        assertThatIllegalStateException()
                .isThrownBy(DataScopeContext::requireCurrent)
                .withMessageContaining("DataScope");
    }
}
