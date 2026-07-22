package com.armada.promotion.channel.service;

import com.armada.promotion.channel.model.dto.PromotionChannelCreateDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.dto.PromotionChannelUpdateDTO;
import com.armada.promotion.channel.model.vo.PromotionChannelDetailVO;
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

    /**
     * 查询当前租户内有效渠道的编辑回显数据。
     *
     * @param id 渠道 ID
     * @return 可直接用于编辑表单回显的数据，不包含任何 Token 材料
     * @throws com.armada.shared.exception.BusinessException 当渠道不存在或已删除时抛出
     */
    PromotionChannelDetailVO detail(Long id);

    /**
     * 编辑当前租户内的有效渠道，保持渠道码和创建信息不变。
     *
     * @param id 渠道 ID
     * @param request 渠道完整编辑参数；平台和追踪 ID 未变且已有完整密文时，空 Token 表示保留原值
     * @throws com.armada.shared.exception.BusinessException 当渠道不存在或编辑参数不符合业务约束时抛出
     */
    void update(Long id, PromotionChannelUpdateDTO request);

    /**
     * 软删除当前租户内的渠道及其追踪配置。
     *
     * <p>域名和历史账号引用继续保留，避免影响共享渠道资产和历史数据。</p>
     *
     * @param id 渠道 ID
     * @throws com.armada.shared.exception.BusinessException 当渠道不存在或已删除时抛出
     */
    void delete(Long id);
}
