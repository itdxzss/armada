package com.armada.task.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.task.model.enums.PullTaskGroupSource;

/** 拉群营销候选群组分页筛选条件。 */
public class PullTaskGroupMarketingCandidateQuery extends PageQuery {

    /** 群组来源筛选；MIXED 表示同时查询历史群和自收群。 */
    private PullTaskGroupSource source;

    /** 群名模糊搜索。 */
    private String keyword;

    /** 群 JID 精确搜索。 */
    private String groupJid;

    /** 当前管理账号手机号模糊搜索。 */
    private String managerPhone;

    /** 当前管理账号所属账号分组。 */
    private Long accountGroupId;

    /** 是否展示只有普通成员关系的群；此类群始终不可选择。 */
    private boolean showRegularGroups;

    /** 最小当前群人数。 */
    private Integer minMemberCount;

    /** 最大当前群人数。 */
    private Integer maxMemberCount;

    /** 是否仅管理员可发言。 */
    private Boolean announceOnly;

    /** 当前创建页等待池标识；只用于识别本等待池占用，不参与 SQL 筛选。 */
    private String reservationToken;

    public PullTaskGroupSource getSource() {
        return source;
    }

    public void setSource(PullTaskGroupSource source) {
        this.source = source;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = blankToNull(keyword);
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = blankToNull(groupJid);
    }

    public String getManagerPhone() {
        return managerPhone;
    }

    public void setManagerPhone(String managerPhone) {
        this.managerPhone = blankToNull(managerPhone);
    }

    public Long getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public boolean isShowRegularGroups() {
        return showRegularGroups;
    }

    public void setShowRegularGroups(boolean showRegularGroups) {
        this.showRegularGroups = showRegularGroups;
    }

    public Integer getMinMemberCount() {
        return minMemberCount;
    }

    public void setMinMemberCount(Integer minMemberCount) {
        this.minMemberCount = nonNegative(minMemberCount);
    }

    public Integer getMaxMemberCount() {
        return maxMemberCount;
    }

    public void setMaxMemberCount(Integer maxMemberCount) {
        this.maxMemberCount = nonNegative(maxMemberCount);
    }

    public Boolean getAnnounceOnly() {
        return announceOnly;
    }

    public void setAnnounceOnly(Boolean announceOnly) {
        this.announceOnly = announceOnly;
    }

    public String getReservationToken() {
        return reservationToken;
    }

    public void setReservationToken(String reservationToken) {
        this.reservationToken = blankToNull(reservationToken);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer nonNegative(Integer value) {
        return value == null || value < 0 ? null : value;
    }
}
