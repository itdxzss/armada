package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 短码必须不可顺序枚举，并满足公网合同的长度与字符集。 */
class HyperlinkShortCodeGeneratorTest {

    @Test
    void generatesUniqueUrlSafeCodesWithinFrozenLength() {
        HyperlinkShortCodeGenerator generator = new HyperlinkShortCodeGenerator();
        Set<String> generated = new HashSet<>();

        for (int index = 0; index < 10_000; index++) {
            String code = generator.next();
            assertThat(code).hasSizeBetween(12, 24).matches("[A-Za-z0-9_-]+");
            assertThat(generated.add(code)).isTrue();
        }
    }
}
