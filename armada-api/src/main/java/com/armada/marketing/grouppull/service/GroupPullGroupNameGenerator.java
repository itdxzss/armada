package com.armada.marketing.grouppull.service;

/** 按 WhatsApp 群名称长度上限生成稳定的“前缀-序号”名称。 */
public final class GroupPullGroupNameGenerator {

    private static final int MAX_GROUP_NAME_LENGTH = 100;

    private GroupPullGroupNameGenerator() {
    }

    public static String generate(String prefix, long sequence) {
        String suffix = "-" + sequence;
        String normalizedPrefix = prefix == null ? "" : prefix.trim();
        int prefixLimit = Math.max(0, MAX_GROUP_NAME_LENGTH - suffix.length());
        if (normalizedPrefix.length() > prefixLimit) {
            normalizedPrefix = normalizedPrefix.substring(0, prefixLimit);
        }
        return normalizedPrefix + suffix;
    }
}
