package com.armada.contact.task;

import com.armada.contact.task.service.ContactSendIntervalPicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 逐条随机发送间隔的纯函数测试。种子固定，断言可重复。 */
class ContactSendIntervalPickerTest {

    @Test
    void keepsSubSecondPrecision() {
        // 竞品最快 0.1 秒；落成整数秒会把这一档做没
        int ms = ContactSendIntervalPicker.pickMs(
                new BigDecimal("0.1"), new BigDecimal("0.1"), new Random(1L));

        assertThat(ms).isEqualTo(100);
    }

    @Test
    void staysInsideClosedRange() {
        Random random = new Random(42L);

        for (int i = 0; i < 200; i++) {
            int ms = ContactSendIntervalPicker.pickMs(
                    new BigDecimal("0.5"), new BigDecimal("3.0"), random);

            assertThat(ms).isBetween(500, 3000);
        }
    }

    @Test
    void variesAcrossConsecutiveCalls() {
        // 逐条随机，不是整轮取一个固定值
        Random random = new Random(7L);
        int first = ContactSendIntervalPicker.pickMs(
                new BigDecimal("1.0"), new BigDecimal("10.0"), random);
        int second = ContactSendIntervalPicker.pickMs(
                new BigDecimal("1.0"), new BigDecimal("10.0"), random);
        int third = ContactSendIntervalPicker.pickMs(
                new BigDecimal("1.0"), new BigDecimal("10.0"), random);

        assertThat(Set.of(first, second, third)).hasSizeGreaterThan(1);
    }

    @Test
    void swapsInvertedBounds() {
        int ms = ContactSendIntervalPicker.pickMs(
                new BigDecimal("3.0"), new BigDecimal("1.0"), new Random(1L));

        assertThat(ms).isBetween(1000, 3000);
    }

    @Test
    void fallsBackToDefaultWhenBoundsAreMissing() {
        assertThat(ContactSendIntervalPicker.pickMs(null, null, new Random(1L)))
                .isEqualTo(ContactSendIntervalPicker.DEFAULT_INTERVAL_MS);
    }

    @Test
    void clampsNonPositiveBoundsToMinimum() {
        // 0 或负间隔会让协议层紧循环发送，必须兜到最小 100ms
        int ms = ContactSendIntervalPicker.pickMs(
                new BigDecimal("0"), new BigDecimal("0"), new Random(1L));

        assertThat(ms).isEqualTo(ContactSendIntervalPicker.MIN_INTERVAL_MS);
    }
}
