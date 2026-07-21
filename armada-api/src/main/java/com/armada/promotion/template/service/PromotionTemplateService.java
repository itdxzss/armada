package com.armada.promotion.template.service;

import com.armada.promotion.template.model.dto.PromotionTemplateQuery;
import com.armada.promotion.template.model.vo.PromotionTemplateVO;
import com.armada.shared.response.PageResult;

/** 推广模板管理服务。 */
public interface PromotionTemplateService {

    /** 分页查询当前请求租户可使用的有效模板。 */
    PageResult<PromotionTemplateVO> page(PromotionTemplateQuery query);
}
