package com.armada.marketing.asset.service;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;

/** 素材标签的 trim、大小写敏感精确去重和安全上限。 */
public final class ResourceAssetTagNormalizer {

    /** 单个素材允许的去重后标签数量上限。 */
    public static final int MAX_TAGS = 20;
    /** 单个标签 trim 后允许的最大字符数。 */
    public static final int MAX_TAG_LENGTH = 64;

    private ResourceAssetTagNormalizer() {
    }

    /**
     * 对标签执行 trim、去空和大小写敏感精确去重，并校验数量与长度上限。
     *
     * @param values 原始标签；空集合或空值按无标签处理
     * @return 保留首次出现顺序的不可变标签集合
     * @throws BusinessException 当元素为空值、超长或去重后超过数量上限时抛出
     */
    public static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "标签必须是字符串");
            }
            String tag = value.trim();
            if (tag.isEmpty()) {
                continue;
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new BusinessException(ErrorCode.VALIDATION, "标签最长 64 个字符");
            }
            if (normalized.add(tag) && normalized.size() > MAX_TAGS) {
                throw new BusinessException(ErrorCode.VALIDATION, "每个素材最多设置 20 个标签");
            }
        }
        return List.copyOf(normalized);
    }
}
