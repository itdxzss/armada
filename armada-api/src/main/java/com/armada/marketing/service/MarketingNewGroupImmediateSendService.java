package com.armada.marketing.service;

import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import java.util.List;

/**
 * 发送中账号动态任务的新群首次即时营销入口。
 */
public interface MarketingNewGroupImmediateSendService {

    /**
     * 为账号本次新增群抢占并写入一次即时营销命令。
     *
     * <p>同任务、同账号 target、同群 JID 只允许一条 {@code round_no=0} attempt；
     * 本方法不推进任务正常轮次，也不修改下一轮时间。</p>
     *
     * @param accountId  发现新群的账号 ID
     * @param groups     本次新增群，按检测顺序排列
     * @param detectedAt 检测时间(epoch 毫秒)
     */
    void enqueueNewGroups(Long accountId, List<MarketingNewGroupDTO> groups, long detectedAt);
}
