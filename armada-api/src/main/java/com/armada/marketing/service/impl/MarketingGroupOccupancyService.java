package com.armada.marketing.service.impl;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.marketing.model.enums.MarketingBusinessType;
import org.springframework.stereotype.Service;

/** 所有营销业务共用的账号分组整组占用服务。 */
@Service
public class MarketingGroupOccupancyService {

    private final AccountGroupMapper accountGroupMapper;

    public MarketingGroupOccupancyService(AccountGroupMapper accountGroupMapper) {
        this.accountGroupMapper = accountGroupMapper;
    }

    /**
     * 尝试原子锁定一个营销分组。
     *
     * @param groupId      账号分组 ID
     * @param businessType 营销任务业务类型
     * @param taskId       占用任务 ID
     * @param now          锁定时间，epoch 毫秒
     * @return 仅本次实际抢占分组时返回 true
     */
    public boolean tryLock(Long groupId,
                           MarketingBusinessType businessType,
                           Long taskId,
                           long now) {
        return accountGroupMapper.tryLockMarketingOccupancy(
                groupId, businessType.code(), taskId, now) == 1;
    }

    /**
     * 按分组、业务类型和任务归属原子释放营销分组。
     *
     * @param groupId      账号分组 ID
     * @param businessType 营销任务业务类型
     * @param taskId       当前占用任务 ID
     * @param now          释放时间，epoch 毫秒
     * @return 仅当前任务实际释放分组时返回 true
     */
    public boolean release(Long groupId,
                           MarketingBusinessType businessType,
                           Long taskId,
                           long now) {
        return accountGroupMapper.releaseMarketingOccupancy(
                groupId, businessType.code(), taskId, now) == 1;
    }

    /** 判断分组当前是否完全空闲。 */
    public boolean isFree(Long groupId) {
        return accountGroupMapper.countMarketingOccupancy(groupId) == 0;
    }
}
