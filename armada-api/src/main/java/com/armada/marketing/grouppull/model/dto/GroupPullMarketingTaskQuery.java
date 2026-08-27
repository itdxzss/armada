package com.armada.marketing.grouppull.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.shared.security.DataScope;

/**
 * 拉群营销任务一级列表查询条件。
 *
 * <p>通过 {@code @ModelAttribute} 绑定，因此使用可变类并继承统一分页参数。</p>
 */
public class GroupPullMarketingTaskQuery extends PageQuery {

    /** 统一营销任务 ID，精准匹配。 */
    private Long id;

    /** 任务名称关键词，模糊匹配。 */
    private String keyword;

    /** 任务主状态码。 */
    private Integer status;

    /** 当前执行阻塞原因码。 */
    private Integer blockReason;

    /** 营销分组及账号资源状态码。 */
    private Integer resourceStatus;

    /** 服务端从可信身份注入的数据范围，不参与 HTTP 绑定。 */
    private DataScope dataScope;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getBlockReason() {
        return blockReason;
    }

    public void setBlockReason(Integer blockReason) {
        this.blockReason = blockReason;
    }

    public Integer getResourceStatus() {
        return resourceStatus;
    }

    public void setResourceStatus(Integer resourceStatus) {
        this.resourceStatus = resourceStatus;
    }

    public DataScope getDataScope() {
        return dataScope;
    }

    /** 仅供 Service 注入服务端范围。 */
    public void applyDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }
}
