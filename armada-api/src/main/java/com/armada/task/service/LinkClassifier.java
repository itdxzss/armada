package com.armada.task.service;

import com.armada.group.service.GroupLinkUrls;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 进群链接输入框文本分类:按行拆分、去空、去重保序,严格 https 群链接为有效,否则无效。 */
public final class LinkClassifier {

    private static final String REQUIRED_PREFIX = "https://";

    private LinkClassifier() {
    }

    /** 分类结果:有效群链接 + 无效行(均已去重保序)。 */
    public record Classified(List<String> valid, List<String> invalid) {
    }

    /**
     * 按行拆分原始文本 → trim → 去空行 → 去重保序;必须是 https:// 开头的 WhatsApp 群邀请链接才入 valid,
     * 否则入 invalid。
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
        Set<String> seenValid = new LinkedHashSet<>();
        for (String line : seen) {
            Optional<String> normalized = strictHttpsInviteLink(line);
            if (normalized.isPresent()) {
                String canonical = REQUIRED_PREFIX + normalized.orElseThrow();
                if (seenValid.add(canonical)) {
                    valid.add(canonical);
                }
            } else {
                invalid.add(line);
            }
        }
        return new Classified(valid, invalid);
    }

    private static Optional<String> strictHttpsInviteLink(String line) {
        if (!line.regionMatches(true, 0, REQUIRED_PREFIX, 0, REQUIRED_PREFIX.length())) {
            return Optional.empty();
        }
        return GroupLinkUrls.tryNormalize(line);
    }
}
