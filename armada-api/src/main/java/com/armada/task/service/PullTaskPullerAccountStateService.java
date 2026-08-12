package com.armada.task.service;

/** 接收账号域终态或离线事实，并同步普通拉群任务内的拉手可用性。 */
public interface PullTaskPullerAccountStateService {

    /** 账号事实对任务拉手角色的影响。 */
    enum Unavailability {
        /** 暂时离线：本任务切换拉手，账号重新在线后允许恢复。 */
        OFFLINE("ACCOUNT_NOT_ONLINE", false),
        /** 账号封禁：从本执行行后续派发中移除。 */
        BANNED("ACCOUNT_BANNED", true),
        /** 账号解绑：从本执行行后续派发中移除。 */
        UNBOUND("ACCOUNT_UNBOUND", true);

        private final String reasonCode;
        private final boolean removed;

        Unavailability(String reasonCode, boolean removed) {
            this.reasonCode = reasonCode;
            this.removed = removed;
        }

        /** @return 任务域持久化的稳定原因码 */
        public String reasonCode() {
            return reasonCode;
        }

        /** @return 是否永久移出当前执行行的后续派发 */
        public boolean removed() {
            return removed;
        }
    }

    /**
     * 标记账号当前占用的普通拉群拉手角色不可用，并清除匹配代际的粘性拉手。
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
}
