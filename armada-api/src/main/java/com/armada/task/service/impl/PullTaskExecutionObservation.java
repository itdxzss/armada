package com.armada.task.service.impl;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.armada.task.model.vo.PullTaskExecutionObservationFact;
import com.armada.task.model.vo.PullTaskExecutionObservationVO;
import com.armada.task.model.vo.PullTaskStandardExecutionAggregate;
import java.util.Objects;
import java.util.Set;

/** 将数据库事实解释为运行说明；无写依赖，不参与任何业务状态转换。 */
public final class PullTaskExecutionObservation {

    private static final int ENABLED = 1;
    private static final Set<String> PERMISSION_WAITS = Set.of(
            PullTaskExecutionReasonCode.MANAGER_ADMIN_ACTOR_UNAVAILABLE.name(),
            PullTaskExecutionReasonCode.MANAGER_ADMIN_SETUP_FAILED.name(),
            PullTaskExecutionReasonCode.MANAGER_ADMIN_UNCONFIRMED.name(),
            PullTaskExecutionReasonCode.GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED.name(),
            PullTaskExecutionReasonCode.GROUP_MEMBER_ADD_PERMISSION_DENIED.name());

    private PullTaskExecutionObservation() {
    }

    /**
     * 解释当前快照；没有开始时间的等待不使用 updatedAt 或最近成功时间代替。
     *
     * @param execution 原执行事实，保持不变
     * @param aggregate 当前料子和资源统计
     * @param facts 当前页活动波次/调用事实，缺失时明确标注
     * @param now 服务端取样时间
     * @return 与业务状态独立的只读说明
     */
    public static PullTaskExecutionObservationVO describe(PullTaskGroupExecution execution,
            PullTaskStandardExecutionAggregate aggregate, PullTaskExecutionObservationFact facts,
            long now) {
        Decision decision = decide(execution, aggregate, facts, now);
        boolean active = !decision.state().blocked && facts != null;
        return new PullTaskExecutionObservationVO(
                decision.state().name(), decision.state().label, decision.detail(), decision.nextStep(),
                now, decision.waitStartedAt(), active ? positive(execution.getNextRunAt()) : null,
                active && (decision.state() == State.INTERVAL || decision.state() == State.WAIT_RESULT)
                        ? positive(facts.getNextDispatchAt()) : null,
                active ? facts.getWaveNo() : null,
                active ? facts.getWaveCallSeq() : null,
                active ? facts.getPlannedCallCount() : null);
    }

    private static Decision decide(PullTaskGroupExecution execution,
            PullTaskStandardExecutionAggregate aggregate, PullTaskExecutionObservationFact facts,
            long now) {
        if (terminal(execution.getExecutionStatus())) {
            int unknown = aggregate == null ? 0 : number(aggregate.getUnknownMemberCount());
            return new Decision(State.FINISHED, "本群执行已进入终态",
                    unknown > 0 ? "仍有 " + unknown + " 人结果待核实，不会因查看页面而重新拉入"
                            : "查看已记录的执行结果", null);
        }
        if (Objects.equals(execution.getManualPaused(), ENABLED)) {
            return new Decision(State.PAUSED, "本群已暂停推进", "查看暂停原因，满足条件后再恢复", null);
        }
        if (facts == null || facts.getTaskStatus() == null) {
            return new Decision(State.UNOBSERVED, "缺少本次运行观察数据", "刷新后查看；仍缺失时排查后台读取", null);
        }
        if (!PullTaskStandardStatus.EXECUTING.name().equals(facts.getTaskStatus())) {
            return parentBlocked(facts.getTaskStatus());
        }
        if (Objects.equals(execution.getExecutionStatus(), PullTaskExecutionStatus.WAIT_RESOURCE.code())) {
            return resourceWait(execution, aggregate);
        }
        if (execution.getActivePullWaveId() != null && facts.getWaveId() == null) {
            return new Decision(State.UNOBSERVED, "活动波次游标尚未对应到有效波次记录",
                    "刷新后核对活动波次与执行行状态", null);
        }
        if (inconsistent(facts)) {
            return new Decision(State.BATCH_INCONSISTENT,
                    "未提交批次计划 " + facts.getPlannedMaterialCount() + " 人，实际绑定 "
                            + facts.getBoundMaterialCount() + " 人",
                    "等待后台校正批次；持续不变时排查批次准备流程", null);
        }
        return active(execution, aggregate, facts, now);
    }

    private static Decision active(PullTaskGroupExecution execution,
            PullTaskStandardExecutionAggregate aggregate, PullTaskExecutionObservationFact facts,
            long now) {
        if (Objects.equals(facts.getCallStatus(), PullTaskPullCallStatus.PLANNED.code())
                && (facts.getCommandId() != null || facts.getSubmittedAt() != null)) {
            return new Decision(State.WAIT_RESULT, "批次已有命令记录，调用状态仍待确认",
                    "核对命令和结果回写，不按未提交批次处理", positive(facts.getSubmittedAt()));
        }
        if (Objects.equals(facts.getCallStatus(), PullTaskPullCallStatus.SUBMITTED.code())) {
            return new Decision(State.WAIT_RESULT, "本批命令已提交，后台尚未完成结果回写",
                    "等待结果回写或超时收口；没有确认的结果保留未知", positive(facts.getSubmittedAt()));
        }
        if (positive(facts.getActionSubmittedAt()) != null) {
            return new Decision(State.WAIT_ACTION_RESULT, "仍有已提交的账号动作等待结果回写",
                    "查看执行动作明细与当前阶段条件", positive(facts.getActionSubmittedAt()));
        }
        if (Objects.equals(facts.getWaveStatus(), PullTaskPullWaveStatus.COLLECTING.code())) {
            return new Decision(State.SETTLING, "本轮已进入结果收口阶段",
                    "完成参与者结果收口后决定后续波次", null);
        }
        if (Objects.equals(facts.getCallStatus(), PullTaskPullCallStatus.PLANNED.code())
                && future(facts.getNextDispatchAt(), now)) {
            return new Decision(State.INTERVAL, "尚未到本轮下一批允许派发时间",
                    "到可派发时间后由调度器继续检查", null);
        }
        if (aggregate != null && number(aggregate.getSubmittedMemberCount()) > 0) {
            return new Decision(State.WAIT_RESULT, "仍有已提交料子等待结果，等待起点未记录",
                    "等待结果回写或超时收口", null);
        }
        if (execution.getLockOwner() != null && future(execution.getLockExpiresAt(), now)) {
            return new Decision(State.CLAIMED, "调度器已领取本群，当前调度租约尚有效",
                    "等待本次调度推进结果，后续刷新核对", null);
        }
        if (future(execution.getNextRunAt(), now)) {
            return new Decision(State.SCHEDULED, "尚未到本群下一次调度检查时间",
                    "到期后调度器重新检查执行条件", null);
        }
        if (Objects.equals(facts.getCallStatus(), PullTaskPullCallStatus.PLANNED.code())) {
            return new Decision(State.PREPARING, "当前批次已计划，尚未提交协议命令",
                    "调度器检查资源与批次后提交", null);
        }
        return new Decision(State.READY, "等待调度器推进当前阶段",
                "按任务并发和资源条件调度；是否停滞需结合后续快照判断", null);
    }

    private static Decision parentBlocked(String status) {
        if (PullTaskStandardStatus.PAUSED.name().equals(status)) {
            return new Decision(State.PAUSED, "所属任务已暂停", "恢复任务后才会继续推进", null);
        }
        if (PullTaskStandardStatus.WAIT_GROUP_RESOURCE.name().equals(status)) {
            return new Decision(State.TASK_BLOCKED, "所属任务等待群资源", "补充群资源并继续任务", null);
        }
        if (PullTaskStandardStatus.WAIT_START.name().equals(status)) {
            return new Decision(State.TASK_BLOCKED, "所属任务尚未开始", "启动任务后进入调度", null);
        }
        return new Decision(State.TASK_BLOCKED, "所属任务当前未在执行", "查看任务状态和中断原因", null);
    }

    private static Decision resourceWait(PullTaskGroupExecution execution,
            PullTaskStandardExecutionAggregate aggregate) {
        Integer type = execution.getWaitResourceType();
        if (execution.getReasonCode() != null && PERMISSION_WAITS.contains(execution.getReasonCode())) {
            return new Decision(State.WAIT_RESOURCE, "当前受群权限或管理员设置条件阻塞",
                    "核实在群管理员权限与群加人设置", null);
        }
        if (Objects.equals(type, PullTaskWaitResourceType.APPROVAL.code())) {
            return new Decision(State.WAIT_APPROVAL, "已提交进群申请，等待群管理员审批",
                    "审批结果确认后继续", null);
        }
        String role = "执行账号";
        Integer planned = null;
        Integer current = null;
        if (Objects.equals(type, PullTaskWaitResourceType.PULLER.code())) {
            role = "拉手";
            planned = aggregate == null ? null : aggregate.getPlannedPullerCount();
            current = aggregate == null ? null : aggregate.getCurrentPullerCount();
        } else if (Objects.equals(type, PullTaskWaitResourceType.MANAGER.code())) {
            role = "管理员";
            planned = aggregate == null ? null : aggregate.getRequiredManagerCount();
            current = aggregate == null ? null : aggregate.getCurrentManagerCount();
        } else if (Objects.equals(type, PullTaskWaitResourceType.STATION.code())) {
            role = "站台";
            planned = aggregate == null ? null : aggregate.getPlannedStationCount();
            current = aggregate == null ? null : aggregate.getCurrentStationCount();
        }
        String next = planned != null && current != null && planned > current
                ? "需补充 " + (planned - current) + " 个可用" + role
                : "检查" + role + "的可用性与进群条件";
        return new Decision(State.WAIT_RESOURCE, "当前等待" + role + "资源或执行条件满足", next, null);
    }

    private static boolean inconsistent(PullTaskExecutionObservationFact facts) {
        return Objects.equals(facts.getCallStatus(), PullTaskPullCallStatus.PLANNED.code())
                && facts.getCommandId() == null && facts.getSubmittedAt() == null
                && facts.getPlannedMaterialCount() != null && facts.getBoundMaterialCount() != null
                && facts.getPlannedMaterialCount().longValue() != facts.getBoundMaterialCount();
    }

    private static boolean terminal(Integer status) {
        return Objects.equals(status, PullTaskExecutionStatus.COMPLETED.code())
                || Objects.equals(status, PullTaskExecutionStatus.FAILED.code())
                || Objects.equals(status, PullTaskExecutionStatus.ABANDONED.code());
    }

    private static Long positive(Long time) { return time != null && time > 0 ? time : null; }
    private static boolean future(Long time, long now) { return time != null && time > now; }
    private static int number(Integer value) { return value == null ? 0 : value; }

    /** 一项运行解释及其可核实的等待起点。 */
    private record Decision(State state, String detail, String nextStep, Long waitStartedAt) { }

    /** 展示状态不写入 execution_status，也不参与调度筛选。 */
    private enum State {
        /** 群执行终态。 */ FINISHED("执行已结束", true),
        /** 人工暂停。 */ PAUSED("已暂停推进", true),
        /** 父任务不在执行。 */ TASK_BLOCKED("任务未在执行", true),
        /** 缺少观察事实。 */ UNOBSERVED("运行情况待确认", true),
        /** 等资源。 */ WAIT_RESOURCE("等待资源", false),
        /** 等进群审批。 */ WAIT_APPROVAL("等待审批", false),
        /** 未提交计划与绑定人数矛盾。 */ BATCH_INCONSISTENT("批次数据异常", false),
        /** 已提交拉人结果尚未收口。 */ WAIT_RESULT("等待结果", false),
        /** 已提交账号动作。 */ WAIT_ACTION_RESULT("等待动作结果", false),
        /** 波次收口阶段。 */ SETTLING("后台结果收口", false),
        /** 有明确未来派发时间。 */ INTERVAL("正常间隔", false),
        /** 未到调度检查时间，不推断具体退避原因。 */ SCHEDULED("等待下次调度", false),
        /** 已有未过期调度租约，不据此推断协议正在执行。 */ CLAIMED("调度已领取", false),
        /** 调用计划未提交。 */ PREPARING("批次准备中", false),
        /** 尚无更细运行事实。 */ READY("待调度推进", false);

        private final String label;
        private final boolean blocked;
        State(String label, boolean blocked) { this.label = label; this.blocked = blocked; }
    }
}
