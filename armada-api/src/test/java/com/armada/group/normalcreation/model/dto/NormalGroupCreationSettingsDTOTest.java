package com.armada.group.normalcreation.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NormalGroupCreationSettingsDTOTest {

    @Test
    void defaultsMatchControlProductDefaults() {
        NormalGroupCreationSettingsDTO defaults = NormalGroupCreationSettingsDTO.defaults();

        assertThat(defaults.sendMessagesAllowed()).isTrue();
        assertThat(defaults.editGroupSettingsAllowed()).isFalse();
        assertThat(defaults.addMembersAllowed()).isTrue();
        assertThat(defaults.joinApprovalEnabled()).isFalse();
        assertThat(defaults.ephemeralDurationSeconds()).isZero();
    }

    @Test
    void normalizedOnlyFillsMissingFieldsAndPreservesExplicitChoices() {
        NormalGroupCreationSettingsDTO normalized =
                new NormalGroupCreationSettingsDTO(null, true, null, true, null).normalized();

        assertThat(normalized.sendMessagesAllowed()).isTrue();
        assertThat(normalized.editGroupSettingsAllowed()).isTrue();
        assertThat(normalized.addMembersAllowed()).isTrue();
        assertThat(normalized.joinApprovalEnabled()).isTrue();
        assertThat(normalized.ephemeralDurationSeconds()).isZero();
    }
}
