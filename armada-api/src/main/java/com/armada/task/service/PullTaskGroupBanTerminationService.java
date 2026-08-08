package com.armada.task.service;

/** WhatsApp 明确封禁群组后，终止普通群链接任务中对应群执行行。 */
public interface PullTaskGroupBanTerminationService {

    /**
     * 终止指定租户、群入口下仍在执行的普通拉群执行行；重复调用幂等。
     *
     * @param tenantId 租户 ID
     * @param groupLinkId 群入口 ID
     */
    void terminateBannedGroup(long tenantId, long groupLinkId);
}
