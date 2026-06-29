package com.armada.group.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroupLinkLabelQueryTest {

    @Test
    void statusHelpersIgnoreCaseAndWhitespace() {
        GroupLinkLabelQuery query = new GroupLinkLabelQuery();

        query.setStatus(" partial ");

        assertThat(query.isStatusPartial()).isTrue();
        assertThat(query.isStatusEmpty()).isFalse();
        assertThat(query.isStatusDone()).isFalse();
        assertThat(query.isStatusFailed()).isFalse();
    }
}
