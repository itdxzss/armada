package com.armada.task.service;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.task.model.dto.JoinTaskCleanupContext;
import com.armada.task.model.dto.JoinTaskCleanupWork;
import com.armada.task.model.enums.JoinTaskCleanupStatus;
import java.util.List;
import org.springframework.stereotype.Service;

/** 无数据库事务的单步协议执行；名单只读一次，不增加权限复核或移除后回读。 */
@Service
public class JoinTaskCleanupProtocol {
    private final AccountProtocolLookupService accounts;
    private final FixedAccountGroupMetadataPort metadata;
    private final GroupParticipantPort participants;
    private final GroupLeavePort leave;

    /** 复用双协议能力端口，以账号协议绑定决定路由。 */
    public JoinTaskCleanupProtocol(AccountProtocolLookupService accounts, FixedAccountGroupMetadataPort metadata,
            GroupParticipantPort participants, GroupLeavePort leave) {
        this.accounts = accounts; this.metadata = metadata; this.participants = participants; this.leave = leave;
    }

    /** 只执行已持久化的一步；任何非明确成功均抛出，禁止继续下一目标。 */
    public JoinTaskCleanupContext execute(JoinTaskCleanupWork work) {
        var row = work.result();
        var state = JoinTaskCleanupStatus.of(work.cleanup().getStatus());
        if (state == JoinTaskCleanupStatus.LISTING) {
            var newAccount = online(row.getAccountId());
            var original = online(row.getAdminActorAccountId());
            if (newAccount.armadaAccountId().equals(original.armadaAccountId())) {
                throw new IllegalArgumentException("新管理员与原执行账号不能相同");
            }
            var snapshot = metadata.getMetadata(newAccount, row.getGroupJid());
            if (snapshot == null || !row.getGroupJid().equals(snapshot.groupJid())
                    || !snapshot.participantsComplete() || snapshot.stateAbnormal()) {
                throw new IllegalArgumentException("群管理员名单不完整或不可用");
            }
            return new JoinTaskCleanupContext(JoinTaskCleanupTargets.select(snapshot.participants(),
                    newAccount.wsPhone(), original.wsPhone()), 0, newAccount.wsPhone(), original.wsPhone(),
                    JoinTaskCleanupTargets.originalAbsent(snapshot.participants(), original.wsPhone()));
        }
        var context = JoinTaskCleanupContext.parse(work.cleanup().getContextJson());
        if (state == JoinTaskCleanupStatus.REMOVING) {
            var newAccount = online(row.getAccountId());
            requireSamePhone(newAccount, context.newPhone());
            var target = context.targets().get(context.completed());
            var response = participants.updateParticipants(newAccount, row.getGroupJid(),
                    List.of(target.jid()), GroupParticipantAction.REMOVE);
            if (response == null || response.results() == null || response.results().size() != 1) {
                throw new IllegalArgumentException("移除回执缺失或结果不完整");
            }
            var item = response.results().get(0);
            if (!target.jid().equals(item.jid()) || !"OK".equals(item.status())) {
                String code = item.rawStatus() == null ? "" : item.rawStatus();
                throw new IllegalArgumentException("移除未成功，状态=" + item.status() + "，原因="
                        + code.substring(0, Math.min(80, code.length())));
            }
            if (response.partial()) throw new IllegalArgumentException("移除回执标记为部分结果，后续已停止");
            return context.advance();
        }
        if (state == JoinTaskCleanupStatus.LEAVING) {
            if (context.originalAlreadyAbsent()) return context;
            var original = online(row.getAdminActorAccountId());
            requireSamePhone(original, context.originalPhone());
            leave.leave(original, row.getGroupJid());
            return context;
        }
        throw new IllegalArgumentException("清理阶段不可执行");
    }

    /** 对协议异常仅保留稳定错误码，避免把凭据或响应体写进任务原因。 */
    public String failureReason(RuntimeException error) {
        if (error instanceof ProtocolException protocol) return protocol.errorCode().name();
        if (error instanceof IllegalArgumentException) return error.getMessage();
        return "执行异常：" + error.getClass().getSimpleName();
    }

    private ProtocolAccountRef online(Long id) {
        if (id == null) throw new IllegalArgumentException("原执行账号未记录，无法完成退群");
        return accounts.findOnlineProtocolRefs(List.of(id)).stream()
                .filter(ref -> id.equals(ref.armadaAccountId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("执行账号离线或不可用"));
    }

    private void requireSamePhone(ProtocolAccountRef ref, String expected) {
        if (!ref.wsPhone().equals(expected)) throw new IllegalArgumentException("执行账号身份已变化");
    }
}
