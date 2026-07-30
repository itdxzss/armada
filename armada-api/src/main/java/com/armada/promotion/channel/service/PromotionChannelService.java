package com.armada.promotion.channel.service;

import com.armada.promotion.channel.model.dto.PromotionChannelCreateDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelCapiEventDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.dto.PromotionChannelProbeDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelUpdateDTO;
import com.armada.promotion.channel.model.vo.FacebookStandardEventVO;
import com.armada.promotion.channel.model.vo.PromotionChannelDetailVO;
import com.armada.promotion.channel.model.vo.PromotionChannelCapiDeliveryResult;
import com.armada.promotion.channel.model.vo.PromotionChannelProbeVO;
import com.armada.promotion.channel.model.vo.PromotionChannelPairingContextRow;
import com.armada.promotion.channel.model.vo.PromotionChannelRuntimeVO;
import com.armada.promotion.channel.model.vo.PromotionChannelVO;
import com.armada.shared.response.PageResult;

/** 渠道管理业务接口。 */
public interface PromotionChannelService {

    /**
     * 返回渠道表单允许配置的 Facebook 官方标准事件目录。
     *
     * @return 顺序稳定的 18 个目录项
     */
    java.util.List<FacebookStandardEventVO> facebookStandardEvents();

    /**
     * 使用当前租户、当前渠道的有效 Facebook 配置投递正式业务事件。
     *
     * @param event 已脱敏的 Outbox 事件
     * @return 可供 Outbox 判定成功、重试或永久失败的脱敏结果
     */
    PromotionChannelCapiDeliveryResult deliverFacebookCapi(PromotionChannelCapiEventDTO event);

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
     * 按公开渠道码和请求域名读取落地页运行时配置。
     *
     * @param channelCode 公开推广码
     * @param forwardedHost Nginx 传入的原始访问域名
     * @return 页面渲染所需的最小配置
     * @throws com.armada.shared.exception.BusinessException 当参数非法、渠道停用或域名不匹配时抛出
     */
    PromotionChannelRuntimeVO runtime(String channelCode, String forwardedHost);

    /**
     * 按公开渠道码和访问域名解析配对所需的可信渠道上下文。
     *
     * <p>该方法是 pairing 域读取渠道数据的 Service 边界；不会返回追踪 Token 等敏感配置。</p>
     *
     * @param channelCode 公开推广码
     * @param forwardedHost 边缘代理传入的原始访问域名
     * @return 渠道所属租户、归属用户和代理地区等最小上下文
     * @throws com.armada.shared.exception.BusinessException 当渠道不存在、停用或域名不匹配时抛出
     */
    PromotionChannelPairingContextRow resolvePairingContext(String channelCode, String forwardedHost);

    /**
     * 发送 Facebook CAPI 测试事件并保存最近探测结论。
     *
     * <p>平台不支持或配置不完整属于可展示失败结果；只有请求本身非法时才抛业务异常。</p>
     *
     * @param id 当前租户内渠道 ID
     * @param request Meta 测试事件码
     * @return 不包含 Token 材料的探测详情
     * @throws com.armada.shared.exception.BusinessException 当渠道不存在、测试码非法或重复探测时抛出
     */
    PromotionChannelProbeVO probe(Long id, PromotionChannelProbeDTO request);

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
