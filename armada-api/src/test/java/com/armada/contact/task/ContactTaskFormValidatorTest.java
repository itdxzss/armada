package com.armada.contact.task;

import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.contact.task.service.ContactTaskFormValidator;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactTaskFormValidatorTest {

    private final ContactTaskFormValidator validator = new ContactTaskFormValidator();

    private static ContactTaskFormDTO linkForm() {
        return new ContactTaskFormDTO(
                "春节福利-好友群发", 0, "限时领取红包", "一句话补充", "https://example.com/promo",
                "老朋友专享福利", new BigDecimal("0.5"), new BigDecimal("1.0"),
                10, 50, 3, "now", 0, 1, "{}");
    }

    private static ContactTaskFormDTO imageForm() {
        return new ContactTaskFormDTO(
                "图文任务", 1, null, null, null, "配图文案",
                new BigDecimal("0.5"), new BigDecimal("1.0"),
                10, 50, 3, "now", 0, 1, "{}");
    }

    @Test
    void acceptsValidLinkForm() {
        assertThat(validator.validate(linkForm()).name()).isEqualTo("春节福利-好友群发");
    }

    @Test
    void acceptsValidImageFormWithoutLinkFields() {
        ContactTaskFormDTO normalized = validator.validate(imageForm());
        // 图文消息的三个链接字段一律清空，不允许写脏数据
        assertThat(normalized.title()).isNull();
        assertThat(normalized.description()).isNull();
        assertThat(normalized.promotionLink()).isNull();
    }

    @Test
    void linkMessageRequiresTitleDescriptionAndLink() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 0, null, "d", "https://a.com", "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息标题");

        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 0, "t", null, "https://a.com", "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("链接描述");

        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 0, "t", "d", "  ", "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("推广链接");
    }

    @Test
    void nameAndContentAreAlwaysRequired() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "  ", 0, "t", "d", "https://a.com", "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务名称");

        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "  ",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内容");
    }

    @Test
    void messageTypeMustBeZeroOrOne() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 3, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息类型");
    }

    @Test
    void intervalIsRoundedToOneDecimalAndMaxIsLiftedToMin() {
        ContactTaskFormDTO normalized = validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("1.26"), new BigDecimal("0.4"),
                10, 50, 3, "now", 0, 1, "{}"));

        // 竞品：Math.round(x*10)/10，且 max 被抬到不小于 min
        assertThat(normalized.msgIntervalMinSec()).isEqualByComparingTo("1.3");
        assertThat(normalized.msgIntervalMaxSec()).isEqualByComparingTo("1.3");
    }

    @Test
    void intervalBelowFloorIsRejected() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.0"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发送间隔");
    }

    @Test
    void numericBoundsFollowCompetitorControls() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 0, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最大执行账号数");
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 201, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最大执行账号数");

        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, -1, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("每号最大发送数");

        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 11, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重试次数");
    }

    @Test
    void scheduledModeRequiresPositiveDelayOnlyWhenEnabled() {
        // 启用 + 延后 + 延迟为 0 → 拒绝
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "scheduled", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("延迟");

        // 未启用时同样的表单允许保存
        assertThat(validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "scheduled", 0, 0, "{}"))
                .taskDelayMinutes()).isZero();
    }

    @Test
    void immediateModeForcesDelayToZero() {
        ContactTaskFormDTO normalized = validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 30, 1, "{}"));

        assertThat(normalized.taskDelayMinutes()).isZero();
    }

    @Test
    void unknownStartModeIsRejected() {
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "cron", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("启动方式");
    }

    @Test
    void overlongTextFieldsAreRejectedNotTruncated() {
        String tooLongName = "n".repeat(129);
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                tooLongName, 1, null, null, null, "c",
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务名称");

        String tooLongContent = "c".repeat(2001);
        assertThatThrownBy(() -> validator.validate(new ContactTaskFormDTO(
                "x", 1, null, null, null, tooLongContent,
                new BigDecimal("0.5"), new BigDecimal("1.0"), 10, 50, 3, "now", 0, 1, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内容");
    }
}
