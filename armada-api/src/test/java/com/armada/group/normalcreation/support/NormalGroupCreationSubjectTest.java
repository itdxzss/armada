package com.armada.group.normalcreation.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class NormalGroupCreationSubjectTest {

    @Test
    void nullOrBlankTemplateNormalizesToAutomaticMarker() {
        assertThat(NormalGroupCreationSubject.normalizeTemplate(null)).isEmpty();
        assertThat(NormalGroupCreationSubject.normalizeTemplate("   ")).isEmpty();
        assertThat(NormalGroupCreationSubject.isAutomatic(
                NormalGroupCreationSubject.normalizeTemplate("   "))).isTrue();
    }

    @Test
    void randomPrefixContainsExactlyNineUppercaseLetters() {
        assertThat(NormalGroupCreationSubject.randomPrefix(new SecureRandom()))
                .matches("[A-Z]{9}");
    }

    @Test
    void blankTemplateAppendsLastFiveCharactersOfGroupJidLocalPart() {
        assertThat(NormalGroupCreationSubject.finalizeAfterCreate(
                "", "ABCDEFGHI", "120363000001234@g.us"))
                .isEqualTo("ABCDEFGHI01234");
    }

    @Test
    void explicitTemplateKeepsFrozenSubject() {
        assertThat(NormalGroupCreationSubject.finalizeAfterCreate(
                "项目群-{no}", "项目群-1", "120363000001234@g.us"))
                .isEqualTo("项目群-1");
    }

    @Test
    void automaticSubjectRejectsMalformedFrozenPrefix() {
        assertThatThrownBy(() -> NormalGroupCreationSubject.finalizeAfterCreate(
                "", "不是九位字母", "120363000001234@g.us"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
