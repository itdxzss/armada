package com.armada.marketing.mapper;

import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.shared.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 营销模板图片文件数据访问。tenant_id 由租户行隔离拦截器自动注入。
 */
@Mapper
public interface MarketingTemplateFileMapper {

    /** 插入图片文件并回填 id。 */
    int insert(MarketingTemplateFile file);

    /** 按 ID 查询未删图片文件。 */
    MarketingTemplateFile selectById(@Param("id") Long id);

    /** 用户请求按 ID 查询图片，缺失或 SYSTEM 范围时不返回数据。 */
    MarketingTemplateFile selectByIdForScope(@Param("id") Long id,
                                             @Param("scope") DataScope scope);
}
