package com.armada.hyperlink.click.model.vo;

import java.math.BigDecimal;

/** 一个分析阈值的命中数量和占成功发送去重号码的比例。 */
public record HyperlinkClickAnalysisBucketVO(
        int threshold,
        long count,
        BigDecimal percent) {
}
