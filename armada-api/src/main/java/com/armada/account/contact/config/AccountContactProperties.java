package com.armada.account.contact.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 账号通讯录采集配置。
 *
 * @param syncOnOnline 账号上线后是否自动同步一次通讯录，未配置时为 true
 * @param snapshotTtlHours 快照有效期小时数，未配置时为 24；小于等于 0 表示每次读取都重拉
 */
@ConfigurationProperties(prefix = "armada.account-contact")
public record AccountContactProperties(
        Boolean syncOnOnline,
        Integer snapshotTtlHours
) {

    /** 未配置时默认在上线后同步。 */
    public boolean syncOnOnlineOrDefault() {
        return syncOnOnline == null || syncOnOnline;
    }

    /** 未配置时默认快照有效期 24 小时。 */
    public int snapshotTtlHoursOrDefault() {
        return snapshotTtlHours == null ? 24 : snapshotTtlHours;
    }
}
