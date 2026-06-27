package com.armada.group.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * 群链接列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 */
public class GroupLinkQuery extends PageQuery {

    /** 所属WS链接分组 ID;群组列表主查询可为空,导入链接分组下钻时传入。 */
    private Long labelId;

    /** 关键字模糊搜索(匹配群名称、真实群名、链接、管理员、群主、来源文件)。 */
    private String keyword;

    /** 群状态过滤:UNCHECKED/AVAILABLE/BANNED/LINK_INVALID/UNAVAILABLE。 */
    private String status;

    /** 来源文件名模糊过滤。 */
    private String sourceFileName;

    /** 首次进入群组池来源:1=导入链接 2=进群任务 3=拉群任务 4=自建群。 */
    private Integer origin;

    /** 我方与群关系:1=目标未进群 2=已进群 3=自建拥有。 */
    private Integer membershipState;

    public Long getLabelId() {
        return labelId;
    }

    public void setLabelId(Long labelId) {
        this.labelId = labelId;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public Integer getOrigin() {
        return origin;
    }

    public void setOrigin(Integer origin) {
        this.origin = origin;
    }

    public Integer getMembershipState() {
        return membershipState;
    }

    public void setMembershipState(Integer membershipState) {
        this.membershipState = membershipState;
    }

    public boolean isStatusUnchecked() {
        return statusEquals("UNCHECKED");
    }

    public boolean isStatusAvailable() {
        return statusEquals("AVAILABLE");
    }

    public boolean isStatusBanned() {
        return statusEquals("BANNED");
    }

    public boolean isStatusLinkInvalid() {
        return statusEquals("LINK_INVALID");
    }

    public boolean isStatusUnavailable() {
        return statusEquals("UNAVAILABLE");
    }

    private boolean statusEquals(String expected) {
        return status != null && expected.equalsIgnoreCase(status.trim());
    }
}
