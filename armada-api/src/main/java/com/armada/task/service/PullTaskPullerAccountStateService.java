package com.armada.task.service;

/** 接收账号域在线、终态或离线事实，同步普通拉群任务内的拉手可用性。 */
public interface PullTaskPullerAccountStateService {

    /** 账号事实对任务拉手角色的影响。 */
    enum Unavailability {
        /** 暂时离线：保留原拉手分配，账号重新在线后继续执行。 */
        OFFLINE("ACCOUNT_NOT_ONLINE"),
        /** 账号封禁：从本执行行后续派发中移除。 */
        BANNED("ACCOUNT_BANNED"),
        /** 账号解绑：从本执行行后续派发中移除。 */
        UNBOUND("ACCOUNT_UNBOUND");

        private final String reasonCode;

        Unavailability(String reasonCode) {
            this.reasonCode = reasonCode;
        }

        /** @return 任务域持久化的稳定原因码 */
        public String reasonCode() {
            return reasonCode;
        }
    }

    /**
     * 标记账号当前占用的普通拉群拉手角色不可用；仅账号终态清除粘性拉手。
     *
     * <p>历史角色行和已提交调用保持不变，供迟到回执继续按原 commandId 和拉手代际收口。</p>
     *
     * @param tenantId 账号所属租户
     * @param accountId Armada 账号 ID
     * @param unavailability 账号不可用分类
     * @param occurredAt 账号状态发生时间(epoch 毫秒)
     */
    void markUnavailable(
            long tenantId,
            long accountId,
            Unavailability unavailability,
            long occurredAt);

    /**
     * 恢复已通过账号域实时资格校验的拉手，并在提交后唤醒因离线等待的执行行。
     *
     * <p>只恢复仍占用的临时离线角色，不恢复已移出角色，不修改在途请求及业务间隔。</p>
     *
     * @param tenantId 账号所属租户
     * @param accountId 已通过在线资格校验的 Armada 账号 ID
     * @param occurredAt 上线事件发生时间(epoch 毫秒)
     */
    void markOnline(long tenantId, long accountId, long occurredAt);
}
