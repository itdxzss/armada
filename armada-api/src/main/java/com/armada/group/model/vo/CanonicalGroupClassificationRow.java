package com.armada.group.model.vo;

/** canonical 群当前唯一分类的 Mapper 投影。 */
public record CanonicalGroupClassificationRow(
        String groupJid,
        Integer classificationCode) {
}
