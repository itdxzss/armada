package com.armada.group.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GroupLinkImportResultTest {

    @Test
    void codeRoundTrip() {
        assertThat(GroupLinkImportResult.SUCCESS.code()).isEqualTo(1);
        assertThat(GroupLinkImportResult.SUCCESS.label()).isEqualTo("成功");
        assertThat(GroupLinkImportResult.FAILED.code()).isEqualTo(2);
        assertThat(GroupLinkImportResult.FAILED.label()).isEqualTo("失败");
        assertThat(GroupLinkImportResult.fromCode(1)).isEqualTo(GroupLinkImportResult.SUCCESS);
    }

    @Test
    void fromCodeRejectsUnknown() {
        assertThatThrownBy(() -> GroupLinkImportResult.fromCode(9))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
