package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OnlineAttemptIdGeneratorTest {

    @Test
    void nextId_returnsPrefixedTimestampAndShortRandomSuffix() {
        OnlineAttemptIdGenerator generator = new OnlineAttemptIdGenerator();

        String first = generator.nextId();
        String second = generator.nextId();

        assertThat(first).matches("oa_\\d{14}_[a-z0-9]{6,12}");
        assertThat(second).matches("oa_\\d{14}_[a-z0-9]{6,12}");
        assertThat(second).isNotEqualTo(first);
    }
}
