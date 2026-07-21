package com.armada.promotion.template.mapper;

import com.armada.promotion.template.model.dto.PromotionTemplateQuery;
import com.armada.promotion.template.model.vo.PromotionTemplateRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 推广模板数据访问；tenant_id 由现有 MyBatis 租户拦截器透明注入。 */
@Mapper
public interface PromotionTemplateMapper {

    /** 使用与列表相同的有效模板条件统计当前租户总数。 */
    long countPage(PromotionTemplateQuery query);

    /** 在 MySQL 中完成当前租户模板的稳定排序和分页。 */
    List<PromotionTemplateRow> selectPage(PromotionTemplateQuery query);
}
