package com.armada.marketing.model.dto;

import com.armada.shared.paging.PageQuery;
import com.armada.shared.security.DataScope;

/**
 * 营销模板列表查询。GET 列表用 {@code @ModelAttribute} 绑定,故为可变 class extends PageQuery。
 *
 * <p>对应需求搜索项:ID(精准)、模板名称(模糊)、文本类型、消息类型。推广链接为二期,暂不含。</p>
 */
public class MarketingTemplateQuery extends PageQuery {

    /** 模板 ID,精准匹配;为空不参与筛选。 */
    private Long id;

    /** 模板名称关键词,模糊匹配;为空不参与筛选。 */
    private String keyword;

    /** 文本类型,枚举筛选;为空/"全部类型"不参与。 */
    private String textType;

    /** 消息类型码(1普通/2按钮/3图文内容);为空不参与。 */
    private Integer linkMode;

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

    public String getTextType() {
        return textType;
    }

    public void setTextType(String textType) {
        this.textType = textType;
    }

    public Integer getLinkMode() {
        return linkMode;
    }

    public void setLinkMode(Integer linkMode) {
        this.linkMode = linkMode;
    }

    public DataScope getDataScope() {
        return dataScope;
    }

    /** 仅供 Service 注入服务端范围。 */
    public void applyDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }
}
