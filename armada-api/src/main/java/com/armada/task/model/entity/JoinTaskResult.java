package com.armada.task.model.entity;

import com.armada.task.model.enums.JoinTaskDispatchState;

/**
 * 进群任务明细实体，映射 {@code join_task_result} 表一行。
 *
 * <p>每账号每链接对应一行计划与执行结果。业务结果 {@code status} 与传输过程
 * {@code dispatchState} 分开保存：WAITING/SUBMITTED/APPROVAL 仍是 PENDING，只有明确结果或处理耗尽才进入
 * SUCCESS/FAILED+TERMINAL。时间列均为 BIGINT epoch 毫秒。</p>
 */
public class JoinTaskResult {
    /** 详情查询投影：审核恢复阶段，0 表示未触发。 */
    private int approvalStage;
    /** 详情查询投影：审核处理说明。 */
    private String approvalReason;
    /** 详情查询投影：关闭审核的原管理员。 */
    private Long approvalActorAccountId;
    /** 读取审核恢复阶段。 */
    public int getApprovalStage() { return approvalStage; }
    /** 保存查询投影。 */
    public void setApprovalStage(int value) { approvalStage = value; }
    /** 读取处理原因。 */
    public String getApprovalReason() { return approvalReason; }
    /** 保存处理原因。 */
    public void setApprovalReason(String value) { approvalReason = value; }
    /** 读取关闭审核执行者。 */
    public Long getApprovalActorAccountId() { return approvalActorAccountId; }
    /** 保存关闭审核执行者。 */
    public void setApprovalActorAccountId(Long value) { approvalActorAccountId = value; }
    /** 查询投影：唯一事实存于 join_task_cleanup，不在进群明细表重复落库。 */
    private int cleanupStatus;
    /** 查询投影：清理失败原因。 */
    private String cleanupReason;
    /** 查询投影：固定名单与进度。 */
    private String cleanupContextJson;
    /** 返回清理阶段。 */
    public int getCleanupStatus() { return cleanupStatus; }
    /** 映射清理阶段投影。 */
    public void setCleanupStatus(int value) { cleanupStatus = value; }
    /** 返回清理原因。 */
    public String getCleanupReason() { return cleanupReason; }
    /** 映射清理原因投影。 */
    public void setCleanupReason(String value) { cleanupReason = value; }
    /** 返回固定清理名单。 */
    public String getCleanupContextJson() { return cleanupContextJson; }
    /** 映射固定清理名单投影。 */
    public void setCleanupContextJson(String value) { cleanupContextJson = value; }


    /** 管理员阶段：0无需设置，1待处理，2已提交，3成功，4失败，5结果待核实。 */
    private int adminStatus;

    /** 返回管理员阶段：0无需设置，1待处理，2已提交，3成功，4失败，5结果待核实。 */
    public int getAdminStatus() { return adminStatus; }

    /** 更新管理员阶段：0无需设置，1待处理，2已提交，3成功，4失败，5结果待核实。 */
    public void setAdminStatus(int value) { this.adminStatus = value; }

    /** 当前管理员命令 ID。 */
    private String adminCommandId;

    /** 返回当前管理员命令 ID。 */
    public String getAdminCommandId() { return adminCommandId; }

    /** 更新当前管理员命令 ID。 */
    public void setAdminCommandId(String value) { this.adminCommandId = value; }

    /** 已提交的管理员尝试次数。 */
    private int adminAttemptNo;

    /** 返回已提交的管理员尝试次数。 */
    public int getAdminAttemptNo() { return adminAttemptNo; }

    /** 更新已提交的管理员尝试次数。 */
    public void setAdminAttemptNo(int value) { this.adminAttemptNo = value; }

    /** 执行设置的原有管理员账号 ID。 */
    private Long adminActorAccountId;

    /** 返回执行设置的原有管理员账号 ID。 */
    public Long getAdminActorAccountId() { return adminActorAccountId; }

    /** 更新执行设置的原有管理员账号 ID。 */
    public void setAdminActorAccountId(Long value) { this.adminActorAccountId = value; }

    /** 管理员阶段下一次处理时间及抢占租约。 */
    private Long adminNextExecuteAt;

    /** 返回管理员阶段下一次处理时间及抢占租约。 */
    public Long getAdminNextExecuteAt() { return adminNextExecuteAt; }

    /** 更新管理员阶段下一次处理时间及抢占租约。 */
    public void setAdminNextExecuteAt(Long value) { this.adminNextExecuteAt = value; }

    /** 管理员阶段截止时间。 */
    private Long adminDeadlineAt;

    /** 返回管理员阶段截止时间。 */
    public Long getAdminDeadlineAt() { return adminDeadlineAt; }

    /** 更新管理员阶段截止时间。 */
    public void setAdminDeadlineAt(Long value) { this.adminDeadlineAt = value; }

    /** 管理员阶段失败或等待原因。 */
    private String adminReason;

    /** 返回管理员阶段失败或等待原因。 */
    public String getAdminReason() { return adminReason; }

    /** 更新管理员阶段失败或等待原因。 */
    public void setAdminReason(String value) { this.adminReason = value; }

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 关联的进群任务 ID（→ join_task.id）。 */
    private Long joinTaskId;

    /** 执行账号号码/别名（快照，展示用）。 */
    private String account;

    /** 执行账号 ID（→ account.id；建任务时回填，可空）。 */
    private Long accountId;

    /** 进群链接。 */
    private String link;

    /** 进群结果码：PENDING/SUCCESS/FAILED。中文展示由前端转换。 */
    private String status;

    /** 异步派发状态：WAITING/SUBMITTED/TERMINAL。 */
    private JoinTaskDispatchState dispatchState;

    /** 下一次允许派发时间（epoch 毫秒）；未激活时为空。 */
    private Long nextExecuteAt;

    /** 当前业务尝试的稳定命令 ID。 */
    private String commandId;

    /** 已发起的业务尝试序号，从 1 开始；未派发为 0。 */
    private int attemptNo;

    /** 失败原因（无效链接行建时即写）。 */
    private String reason;

    /** 进群成功后回填群 JID（Kafka promote 匹配）。 */
    private String groupJid;

    /** 是否已成管理员（Kafka participant_changed promote 回写）。 */
    private boolean isAdmin;

    /** 成为管理员时间（epoch 毫秒）；可空。 */
    private Long promotedAt;

    /** 受控账号首次明确进群成功时间（epoch 毫秒）；历史数据可为空。 */
    private Long joinedAt;

    /** 创建时间（epoch 毫秒）。 */
    private Long createdAt;

    /** 更新时间（epoch 毫秒，引擎逐行回写）。 */
    private Long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getJoinTaskId() {
        return joinTaskId;
    }

    public void setJoinTaskId(Long joinTaskId) {
        this.joinTaskId = joinTaskId;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * 返回明细当前异步派发阶段。
     *
     * @return WAITING、SUBMITTED 或 TERMINAL
     */
    public JoinTaskDispatchState getDispatchState() {
        return dispatchState;
    }

    /**
     * 设置明细异步派发阶段。
     *
     * @param dispatchState 派发阶段
     */
    public void setDispatchState(JoinTaskDispatchState dispatchState) {
        this.dispatchState = dispatchState;
    }

    /**
     * 返回当前行下一次允许写入 outbox 的时间。
     *
     * @return epoch 毫秒；未激活或已提交时为空
     */
    public Long getNextExecuteAt() {
        return nextExecuteAt;
    }

    /**
     * 设置当前行下一次允许写入 outbox 的时间。
     *
     * @param nextExecuteAt epoch 毫秒；空值表示该账号尚未轮到本行
     */
    public void setNextExecuteAt(Long nextExecuteAt) {
        this.nextExecuteAt = nextExecuteAt;
    }

    /**
     * 返回当前尝试关联的 outbox 命令 ID。
     *
     * @return commandId；WAITING 或尚未派发时为空
     */
    public String getCommandId() {
        return commandId;
    }

    /**
     * 关联当前业务尝试与 outbox 命令。
     *
     * @param commandId outbox 命令 ID
     */
    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    /**
     * 返回已经发起的业务尝试序号。
     *
     * @return 0 表示尚未派发，首次派发为 1
     */
    public int getAttemptNo() {
        return attemptNo;
    }

    /**
     * 设置已经发起的业务尝试序号。
     *
     * @param attemptNo 非负尝试序号
     */
    public void setAttemptNo(int attemptNo) {
        this.attemptNo = attemptNo;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public boolean isAdmin() {
        return isAdmin;
    }

    public void setAdmin(boolean admin) {
        isAdmin = admin;
    }

    public Long getPromotedAt() {
        return promotedAt;
    }

    public void setPromotedAt(Long promotedAt) {
        this.promotedAt = promotedAt;
    }

    public Long getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Long joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
