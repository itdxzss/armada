package com.armada.account.service;

import com.armada.account.model.vo.SmsActivationVO;
import java.util.List;

/**
 * 接码订单的 Go 注册办理：运营点一次“开始注册”，后台定时任务自动完成发码、等码、验码。
 * 办理期间订单被占用，旧的手动完成/取消入口必须拒绝，出口是办理结束（已注册或需处理）后自动恢复。
 */
public interface SmsRegistrationService {
    /**
     * 对等码订单开始注册：校验订单、按号码国家占用一个代理、保存办理记录。
     * 本方法不调用 Go，也不调用号商，真正的外部调用由定时任务下一轮接手。
     * 重复点击返回已有办理记录，不会再次占用代理。
     *
     * @param smsActivationId 接码订单 ID
     * @param operatorId 当前登录用户 ID
     * @return 附带办理状态的订单
     * @throws com.armada.shared.exception.BusinessException 未启用、订单不可开始、暂无空闲代理时抛出
     */
    SmsActivationVO start(Long smsActivationId, long operatorId);

    /** @return 订单是否正被后台注册占用（申请中、等码、验证中） */
    boolean isOccupied(Long smsActivationId);

    /** @return 当前租户不能开始注册的固定原因码；可用时为空串 */
    String disabledReason();

    /** @return 给订单视图附带办理状态后的新列表，顺序不变；没有办理记录的订单保持原样 */
    List<SmsActivationVO> attach(List<SmsActivationVO> views);
}
