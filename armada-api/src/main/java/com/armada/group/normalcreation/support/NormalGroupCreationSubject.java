package com.armada.group.normalcreation.support;

import java.security.SecureRandom;
import java.util.Objects;

/** 新建普群自动群名的生成与收敛规则。 */
public final class NormalGroupCreationSubject {

    private static final char[] UPPERCASE_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int RANDOM_PREFIX_LENGTH = 9;
    private static final int GROUP_JID_SUFFIX_LENGTH = 5;

    private NormalGroupCreationSubject() {
    }

    /** 未提供群名模板时使用自动命名。 */
    public static boolean isAutomatic(String groupNameTemplate) {
        return groupNameTemplate == null || groupNameTemplate.isBlank();
    }

    /** 将未填写模板统一冻结为空字符串，兼容任务表非空约束。 */
    public static String normalizeTemplate(String groupNameTemplate) {
        return groupNameTemplate == null ? "" : groupNameTemplate.trim();
    }

    /** 建群前生成并冻结 9 位大写随机字母，供 WhatsApp 首次建群使用。 */
    public static String randomPrefix(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        char[] value = new char[RANDOM_PREFIX_LENGTH];
        for (int index = 0; index < value.length; index++) {
            value[index] = UPPERCASE_LETTERS[random.nextInt(UPPERCASE_LETTERS.length)];
        }
        return new String(value);
    }

    /** 建群成功后用已冻结前缀和群 JID 本地部分最后 5 位生成最终群名。 */
    public static String finalizeAfterCreate(
            String groupNameTemplate, String frozenSubject, String groupJid) {
        if (!isAutomatic(groupNameTemplate)) {
            return requireText(frozenSubject, "frozenSubject");
        }
        String prefix = requireText(frozenSubject, "frozenSubject");
        if (prefix.length() != RANDOM_PREFIX_LENGTH
                || !prefix.chars().allMatch(value -> value >= 'A' && value <= 'Z')) {
            throw new IllegalArgumentException("automatic group subject prefix is invalid");
        }
        String normalizedJid = requireText(groupJid, "groupJid");
        int separator = normalizedJid.indexOf('@');
        String localPart = separator < 0 ? normalizedJid : normalizedJid.substring(0, separator);
        if (localPart.length() < GROUP_JID_SUFFIX_LENGTH) {
            throw new IllegalArgumentException("groupJid local part is shorter than 5 characters");
        }
        return prefix + localPart.substring(localPart.length() - GROUP_JID_SUFFIX_LENGTH);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
