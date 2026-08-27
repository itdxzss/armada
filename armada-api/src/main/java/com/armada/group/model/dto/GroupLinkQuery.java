package com.armada.group.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.shared.security.DataScope;
import com.armada.group.model.enums.GroupListType;

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

    /** 群组列表运营分组 ID。 */
    private Long folderId;

    /** 是否只查询未分组群。 */
    private Boolean withoutFolder;

    /** 固化群分类。 */
    private GroupListType groupType;

    /** 是否存在严格可执行的在线管理员账号。 */
    private Boolean availableAdmin;

    /** 最小成员数，含端点。 */
    private Integer memberCountMin;

    /** 最大成员数，含端点。 */
    private Integer memberCountMax;

    /** 六大洲稳定代码。 */
    private String continentCode;

    /** 群主国家 ISO2。 */
    private String countryIso2;

    /** 最小群龄天数，含端点。 */
    private Integer ageDaysMin;

    /** 最大群龄天数，含端点。 */
    private Integer ageDaysMax;

    /** 当前查询统一使用的 Unix 秒；由 service 设置，不接受请求参数语义。 */
    private Long nowSeconds;

    /** 服务端注入的数据范围；不接受 HTTP 参数绑定。 */
    private DataScope dataScope;

    public DataScope getDataScope() {
        return dataScope;
    }

    /** 仅供 Service 注入可信数据范围。 */
    public void applyDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }

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

    /**
     * 返回关键词是否只包含 ASCII 字符。
     *
     * <p>群 JID、邀请码和规范化手机号使用 ascii_bin 排序规则；非 ASCII 关键词
     * 不应参与这些字段的 LIKE 比较，否则 MySQL 会把 ascii_bin 与 utf8mb4 参数
     * 放在同一个 LIKE 表达式中并抛出排序规则冲突。</p>
     */
    public boolean isKeywordAscii() {
        return keyword == null || keyword.chars().allMatch(ch -> ch <= 0x7F);
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

    public Long getFolderId() {
        return folderId;
    }

    public void setFolderId(Long folderId) {
        this.folderId = folderId;
    }

    public Boolean getWithoutFolder() {
        return withoutFolder;
    }

    public void setWithoutFolder(Boolean withoutFolder) {
        this.withoutFolder = withoutFolder;
    }

    public GroupListType getGroupType() {
        return groupType;
    }

    public void setGroupType(GroupListType groupType) {
        this.groupType = groupType;
    }

    public Boolean getAvailableAdmin() {
        return availableAdmin;
    }

    public void setAvailableAdmin(Boolean availableAdmin) {
        this.availableAdmin = availableAdmin;
    }

    public Integer getMemberCountMin() {
        return memberCountMin;
    }

    public void setMemberCountMin(Integer memberCountMin) {
        this.memberCountMin = memberCountMin;
    }

    public Integer getMemberCountMax() {
        return memberCountMax;
    }

    public void setMemberCountMax(Integer memberCountMax) {
        this.memberCountMax = memberCountMax;
    }

    public String getContinentCode() {
        return continentCode;
    }

    public void setContinentCode(String continentCode) {
        this.continentCode = continentCode;
    }

    public String getCountryIso2() {
        return countryIso2;
    }

    public void setCountryIso2(String countryIso2) {
        this.countryIso2 = countryIso2;
    }

    public Integer getAgeDaysMin() {
        return ageDaysMin;
    }

    public void setAgeDaysMin(Integer ageDaysMin) {
        this.ageDaysMin = ageDaysMin;
    }

    public Integer getAgeDaysMax() {
        return ageDaysMax;
    }

    public void setAgeDaysMax(Integer ageDaysMax) {
        this.ageDaysMax = ageDaysMax;
    }

    public Long getNowSeconds() {
        return nowSeconds;
    }

    public void setNowSeconds(Long nowSeconds) {
        this.nowSeconds = nowSeconds;
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

    public boolean isGroupTypeHistorical() {
        return GroupListType.HISTORICAL == groupType;
    }

    public boolean isGroupTypePostControl() {
        return GroupListType.POST_CONTROL == groupType;
    }

    public boolean isGroupTypeBoth() {
        return GroupListType.BOTH == groupType;
    }

    private boolean statusEquals(String expected) {
        return status != null && expected.equalsIgnoreCase(status.trim());
    }
}
