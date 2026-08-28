package com.armada.contact.task.service;

import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 通讯录营销任务表单校验与归一化。
 *
 * <p>纯逻辑，不碰数据库。约束逐条对齐竞品前端控件（设计文档 §2.4、§2.5）：
 * 越界一律拒绝而不是静默裁剪，避免用户以为设置生效了。
 * 唯一例外是 max 间隔小于 min 时抬到 min——竞品前端本身就是这么做的。</p>
 */
@Component
public class ContactTaskFormValidator {

    /** 链接消息。 */
    private static final int MESSAGE_TYPE_LINK = 0;
    /** 图文消息。 */
    private static final int MESSAGE_TYPE_IMAGE = 1;

    private static final int NAME_MAX = 128;
    private static final int TITLE_MAX = 512;
    private static final int DESCRIPTION_MAX = 2048;
    private static final int LINK_MAX = 2048;
    private static final int CONTENT_MAX = 2000;

    private static final BigDecimal INTERVAL_MIN = new BigDecimal("0.1");
    private static final BigDecimal INTERVAL_MAX = new BigDecimal("60.0");

    private static final int CONCURRENCY_MIN = 1;
    private static final int CONCURRENCY_MAX = 200;
    private static final int RETRY_MAX_LIMIT = 10;

    private static final String START_MODE_NOW = "now";
    private static final String START_MODE_SCHEDULED = "scheduled";

    /**
     * 校验并归一化任务表单。
     *
     * @param form 原始表单
     * @return 归一化后的表单
     * @throws BusinessException 任一约束不满足时抛出
     */
    public ContactTaskFormDTO validate(ContactTaskFormDTO form) {
        if (form == null) {
            throw invalid("任务表单不能为空");
        }
        String name = required(form.name(), "任务名称");
        limit(name, NAME_MAX, "任务名称");

        int messageType = form.messageType() == null ? -1 : form.messageType();
        if (messageType != MESSAGE_TYPE_LINK && messageType != MESSAGE_TYPE_IMAGE) {
            throw invalid("消息类型只能是 0 链接消息或 1 图文消息");
        }

        String content = required(form.content(), "正文内容");
        limit(content, CONTENT_MAX, "正文内容");

        String title = null;
        String description = null;
        String promotionLink = null;
        if (messageType == MESSAGE_TYPE_LINK) {
            title = required(form.title(), "消息标题");
            limit(title, TITLE_MAX, "消息标题");
            description = required(form.description(), "链接描述");
            limit(description, DESCRIPTION_MAX, "链接描述");
            promotionLink = required(form.promotionLink(), "推广链接");
            limit(promotionLink, LINK_MAX, "推广链接");
        }

        BigDecimal min = interval(form.msgIntervalMinSec(), "发送间隔最小值");
        BigDecimal max = interval(form.msgIntervalMaxSec(), "发送间隔最大值");
        // 与竞品前端一致：最大值小于最小值时抬到最小值，而不是报错
        if (max.compareTo(min) < 0) {
            max = min;
        }

        int concurrency = form.concurrency() == null ? 0 : form.concurrency();
        if (concurrency < CONCURRENCY_MIN || concurrency > CONCURRENCY_MAX) {
            throw invalid("最大执行账号数必须在 " + CONCURRENCY_MIN + "~" + CONCURRENCY_MAX + " 之间");
        }

        int maxSends = form.maxSendsPerAccount() == null ? 0 : form.maxSendsPerAccount();
        if (maxSends < 0) {
            throw invalid("每号最大发送数不能为负数，0 表示全部联系人");
        }

        int retryMax = form.retryMax() == null ? 0 : form.retryMax();
        if (retryMax < 0 || retryMax > RETRY_MAX_LIMIT) {
            throw invalid("失败重试次数必须在 0~" + RETRY_MAX_LIMIT + " 之间");
        }

        String startMode = form.startMode() == null ? "" : form.startMode().trim();
        if (!START_MODE_NOW.equals(startMode) && !START_MODE_SCHEDULED.equals(startMode)) {
            throw invalid("启动方式只能是 now 或 scheduled");
        }

        int enabled = form.isEnabled() == null ? 0 : form.isEnabled();
        if (enabled != 0 && enabled != 1) {
            throw invalid("任务开关只能是 0 或 1");
        }

        int delay = form.taskDelayMinutes() == null ? 0 : form.taskDelayMinutes();
        if (START_MODE_NOW.equals(startMode)) {
            // 立即执行时延迟恒为 0，前端也是这么提交的
            delay = 0;
        } else if (enabled == 1 && delay <= 0) {
            // 只有「启用 + 延后」才要求延迟为正数；仅保存草稿时不拦
            throw invalid("延迟时间需大于 0 分钟");
        }

        return new ContactTaskFormDTO(
                name, messageType, title, description, promotionLink, content,
                min, max, concurrency, maxSends, retryMax, startMode, delay, enabled,
                form.accountFilterJson());
    }

    private static BigDecimal interval(BigDecimal value, String label) {
        if (value == null) {
            throw invalid(label + "不能为空");
        }
        BigDecimal rounded = value.setScale(1, RoundingMode.HALF_UP);
        if (rounded.compareTo(INTERVAL_MIN) < 0 || rounded.compareTo(INTERVAL_MAX) > 0) {
            throw invalid("发送间隔必须在 " + INTERVAL_MIN + "~" + INTERVAL_MAX + " 秒之间");
        }
        return rounded;
    }

    private static String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(label + "不能为空");
        }
        return value.trim();
    }

    private static void limit(String value, int max, String label) {
        if (value.length() > max) {
            throw invalid(label + "长度不能超过 " + max);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
