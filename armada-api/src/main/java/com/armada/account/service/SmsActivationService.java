package com.armada.account.service;

import com.armada.account.model.dto.SmsActivationCreateDTO;
import com.armada.account.model.dto.SmsActivationQuery;
import com.armada.account.model.vo.SmsActivationOptionsVO;
import com.armada.account.model.vo.SmsActivationVO;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.shared.response.PageResult;
import java.util.List;

/** 后台手动接码：运营取号、查看验证码、完成或取消；不进入自动注册流水线。 */
public interface SmsActivationService {
    /** 页面初始化：WhatsApp 美国目录、余额与取号开关。 */
    SmsActivationOptionsVO options();
    /** 查询美国目录项的价格档位，库存仅为快照。 */
    List<GrizzlyPriceTier> priceTiers(String countryId);
    /**
     * 同步向平台取一个号并保存；结果不明时不自动重试。
     * @param request 国家、最高价与可选商家
     * @param operatorId 当前登录用户 ID
     * @return 新取得的号码记录
     */
    SmsActivationVO create(SmsActivationCreateDTO request, long operatorId);
    /** 当前租户记录 SQL 分页，不调用平台。 */
    PageResult<SmsActivationVO> list(SmsActivationQuery query);
    /**
     * 查询一条记录；仍在等码时向平台查一次短信状态并回写。
     * 订单被后台注册占用时只读本地：查码并回写由注册定时任务负责，避免两处同时写同一订单。
     */
    SmsActivationVO refresh(Long id);
    /**
     * 向平台查一次短信状态并回写，不检查注册占用；仅供注册定时任务使用，页面请走 {@link #refresh}。
     * @param id 订单 ID
     * @return 刷新后的订单
     */
    SmsActivationVO pollProvider(Long id);
    /** 已收码订单通知平台完成；订单被后台注册占用时拒绝。 */
    SmsActivationVO complete(Long id);
    /** 等码订单在允许取消时间后通知平台取消；订单被后台注册占用时拒绝。 */
    SmsActivationVO cancel(Long id);
}
