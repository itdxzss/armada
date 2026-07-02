package com.armada.task.service;

import com.armada.group.service.GroupLinkUrls;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 进群链接输入框文本分类:按行拆分、去空、去重保序,通过群链接格式校验为有效,否则无效。 */
public final class LinkClassifier {

    private LinkClassifier() {
    }

    /** 分类结果:有效群链接 + 无效行(均已去重保序)。 */
    public record Classified(List<String> valid, List<String> invalid) {
    }

    /**
     * 按行拆分原始文本 → trim → 去空行 → 去重保序;能归一化为 WhatsApp 群邀请链接入 valid,否则入 invalid。
     *
     * @param linksText 输入框原始文本(可空)
     * @return 分类结果
     */
    public static Classified classify(String linksText) {
        List<String> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        if (linksText == null || linksText.isBlank()) {
            return new Classified(valid, invalid);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : linksText.split("\\R")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                seen.add(line);
            }
        }
        for (String line : seen) {
            if (GroupLinkUrls.tryNormalize(line).isPresent()) {
                valid.add(line);
            } else {
                invalid.add(line);
            }
        }
        return new Classified(valid, invalid);
    }
}
