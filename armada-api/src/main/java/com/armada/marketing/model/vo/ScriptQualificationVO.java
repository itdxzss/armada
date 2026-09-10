package com.armada.marketing.model.vo;

import java.util.List;

/** 启动检查结果，未通过时完整保留所有勾选群与缺口。 */
public record ScriptQualificationVO(boolean ready, long accountCount, int requiredPromoters,
        String poolReason, long checkedAt, List<Group> groups) {
    /** 群内可用数仅计算本分组不同推手；缺失事实另列，不能当作通过。 */
    public record Group(Long groupLinkId, String groupJid, String groupName, boolean ready,
            int required, int available, int shortage, int offline, int noPermission,
            int unconfirmed, List<String> reasons) { }
}
