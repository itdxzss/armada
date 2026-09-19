package com.armada.task.service;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.port.GroupApprovalPort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.task.model.dto.JoinTaskApprovalWork;
import com.armada.task.model.enums.JoinTaskApprovalStage;
import org.springframework.stereotype.Service;

/** 事务外执行单个审核恢复步骤；所有写操作只针对本条群和目标账号。 */
@Service
public class JoinTaskApprovalProtocol {
    private final JoinTaskApprovalFacts facts;
    private final GroupApprovalPort approvals;
    private final GroupSettingsPort settings;
    private final GroupJoinPort joins;

    /** 复用账号、群域候选与协议能力，不从任务域直接访问其他域的表。 */
    public JoinTaskApprovalProtocol(JoinTaskApprovalFacts facts, GroupApprovalPort approvals,
            GroupSettingsPort settings, GroupJoinPort joins) {
        this.facts = facts; this.approvals = approvals; this.settings = settings; this.joins = joins;
    }

    /** 返回下一步；VERIFY 返回自身表示仅继续只读核实。 */
    public JoinTaskApprovalStage execute(JoinTaskApprovalWork work) {
        var state = work.approval();
        var stage = JoinTaskApprovalStage.of(state.getStage());
        if (stage == JoinTaskApprovalStage.RESOLVE) return resolve(work);
        var target = facts.target(work);
        var actor = facts.actor(work);
        return switch (stage) {
            case CLOSE -> {
                settings.setJoinApprovalEnabled(actor, state.getGroupJid(), false);
                yield JoinTaskApprovalStage.CHECK;
            }
            case CHECK -> check(work, actor, target);
            case APPROVE -> {
                approvals.approve(actor, state.getGroupJid(), state.getPendingJid());
                yield JoinTaskApprovalStage.VERIFY;
            }
            case REJOIN -> {
                var result = joins.join(new GroupJoinCommand(target, work.result().getLink(),
                        "join-approval:" + state.getResultId() + ":" + state.getVersion()));
                if (result != null && result.groupJid() != null && !result.groupJid().isBlank()
                        && !state.getGroupJid().equals(result.groupJid())) {
                    throw new IllegalArgumentException("邀请链接对应群组已变化");
                }
                yield JoinTaskApprovalStage.VERIFY;
            }
            case VERIFY -> JoinTaskApprovalFacts.member(facts.snapshot(actor, state.getGroupJid()), target.wsPhone()).isPresent()
                    ? JoinTaskApprovalStage.SUCCESS : JoinTaskApprovalStage.VERIFY;
            default -> throw new IllegalArgumentException("审核处理阶段不可执行");
        };
    }

    private JoinTaskApprovalStage resolve(JoinTaskApprovalWork work) {
        var state = work.approval();
        if (work.task().getOwnerUserId() == null) throw new IllegalArgumentException("任务归属身份缺失");
        var target = facts.target(work);
        String group = state.getGroupJid();
        if (group == null || group.isBlank()) group = approvals.resolveGroup(target, work.result().getLink());
        if (!group.matches("[^@\\s]+@g\\.us")) throw new IllegalArgumentException("无法确认目标群组");
        state.setGroupJid(group);
        facts.selectActor(work, group, target);
        return JoinTaskApprovalStage.CLOSE;
    }

    private JoinTaskApprovalStage check(JoinTaskApprovalWork work, ProtocolAccountRef actor, ProtocolAccountRef target) {
        var snapshot = facts.snapshot(actor, work.approval().getGroupJid());
        if (JoinTaskApprovalFacts.member(snapshot, target.wsPhone()).isPresent()) return JoinTaskApprovalStage.SUCCESS;
        if (!snapshot.participantsComplete() || snapshot.participants() == null) return JoinTaskApprovalStage.CHECK;
        var pending = approvals.pending(actor, work.approval().getGroupJid());
        String targetJid = WhatsappJids.userJid(target.wsPhone());
        if (pending.contains(targetJid)) {
            work.approval().setPendingJid(targetJid);
            return JoinTaskApprovalStage.APPROVE;
        }
        // 未知 LID 无法排除它就是当前申请，不能把“不认识”误当“没有申请”再进群。
        if (pending.stream().anyMatch(jid -> !jid.endsWith("@s.whatsapp.net"))) return JoinTaskApprovalStage.CHECK;
        // 完整数组中的未映射 LID 仍无法排除当前目标已经在群内。
        if (snapshot.participants().stream().anyMatch(p -> p.jid() != null && p.jid().endsWith("@lid")
                && (p.pnJid() == null || p.pnJid().isBlank()) && (p.phone() == null || p.phone().isBlank()))) {
            return JoinTaskApprovalStage.CHECK;
        }
        return JoinTaskApprovalStage.REJOIN;
    }

    /** 网络或回执未知只允许转入只读核实，不重放副作用。 */
    public boolean uncertain(RuntimeException error) {
        if (!(error instanceof ProtocolException e)) return false;
        return switch (e.errorCode()) {
            case TIMEOUT, NETWORK, UNKNOWN, JOIN_RESULT_UNCONFIRMED, GROUP_JOIN_UNKNOWN -> true;
            default -> false;
        };
    }

    /** 用户原因不包含协议原始响应或敏感数据。 */
    public String failure(JoinTaskApprovalStage stage, RuntimeException error) {
        String reason;
        if (error instanceof ProtocolException e) {
            reason = switch (e.errorCode()) {
                case GROUP_PERMISSION_DENIED -> "无管理员权限";
                case GROUP_BANNED, GROUP_UNAVAILABLE -> "群组状态异常";
                case ACCOUNT_NOT_ONLINE -> stage == JoinTaskApprovalStage.REJOIN ? "进群账号离线" : "执行账号离线";
                case TIMEOUT -> "请求超时";
                case NETWORK -> "协议连接异常";
                default -> "协议结果未确认（" + e.errorCode().name() + "）";
            };
        } else reason = error instanceof IllegalArgumentException ? error.getMessage() : "执行异常，结果未确认";
        String prefix = stage.code() <= JoinTaskApprovalStage.CLOSE.code()
                ? (uncertain(error) ? "关闭群组审核结果未确认：" : "关闭群组审核失败：")
                : "审核已关闭，完成进群失败：";
        return prefix + reason;
    }
}
