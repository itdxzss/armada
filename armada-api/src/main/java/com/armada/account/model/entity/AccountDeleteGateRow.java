package com.armada.account.model.entity;

/**
 * 批量删除闸门查询结果行:按 id 取 account_state + dispatched_at 用于严格口径校验。
 *
 * <p>裸 POJO + getter/setter(无 Lombok),Mapper 直出。</p>
 */
public class AccountDeleteGateRow {

    /** account.id。 */
    private Long id;

    /**
     * 账号状态:1新增 2正常 3封禁 4导出 5解绑;NULL=未上报(step1 导入态)。
     * 仅 3/4/5 且 dispatched_at IS NULL 才允许删除。
     */
    private Integer accountState;

    /**
     * 首次派单时间(epoch 毫秒;NULL=未分配)。
     * 不为 NULL 表示账号当前正在任务中,禁止删除。
     */
    private Long dispatchedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getAccountState() {
        return accountState;
    }

    public void setAccountState(Integer accountState) {
        this.accountState = accountState;
    }

    public Long getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Long dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }
}
