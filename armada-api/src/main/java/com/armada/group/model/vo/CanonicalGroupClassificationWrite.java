package com.armada.group.model.vo;

/** canonical 群首次分类的单行批量写参数。 */
public record CanonicalGroupClassificationWrite(
        String groupJid,
        int classificationCode,
        int sourceCode,
        long classifiedAt) {
}
