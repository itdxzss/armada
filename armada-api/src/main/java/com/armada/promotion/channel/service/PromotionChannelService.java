package com.armada.promotion.channel.service;

import com.armada.promotion.channel.model.dto.PromotionChannelCreateDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.vo.PromotionChannelVO;
import com.armada.shared.response.PageResult;

/** 渠道管理业务接口。 */
public interface PromotionChannelService {

    /**
     * 在单个事务内创建域名绑定、渠道主记录和可选追踪配置。
     *
     * @param request 渠道新增参数
     * @return 已创建渠道的页面展示数据
     */
    PromotionChannelVO create(PromotionChannelCreateDTO request);

    /**
     * 按国家、模板、创建人或归属用户集合分页查询渠道。
     *
     * @param query 查询条件
     * @return 渠道分页结果
     */
    PageResult<PromotionChannelVO> page(PromotionChannelQuery query);
}
