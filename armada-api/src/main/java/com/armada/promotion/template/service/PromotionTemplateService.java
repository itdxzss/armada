package com.armada.promotion.template.service;

import com.armada.promotion.template.model.dto.PromotionTemplateQuery;
import com.armada.promotion.template.model.dto.PromotionTemplateRemarkUpdateDTO;
import com.armada.promotion.template.model.vo.PromotionTemplateVO;
import com.armada.shared.response.PageResult;

/** 推广模板管理服务。 */
public interface PromotionTemplateService {

    /** 分页查询当前请求租户可使用的有效模板。 */
    PageResult<PromotionTemplateVO> page(PromotionTemplateQuery query);

    /**
     * 修改当前请求租户内有效模板的备注，并同步刷新更新时间。
     *
     * @param id 模板 ID
     * @param request 备注修改参数，空白备注表示清空
     */
    void updateRemark(Long id, PromotionTemplateRemarkUpdateDTO request);
}
