package com.armada.group.normalcreation.service.impl;

import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolNormalGroupCreationCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 把新建普群状态机动作写入通用协议 Outbox。
 *
 * <p>这里是协议 Topic 路由的唯一业务入口。联系人准备按每个方向的 actor 构造命令，
 * 所以 Web 账号只会进入 Web 命令 Topic，Android 账号只会进入 Android 命令 Topic。</p>
 */
@Component
public class NormalGroupCreationCommandDispatcher {

    private static final int OUTBOX_BATCH_SIZE = 500;

    private final NormalGroupCreationMapper mapper;
    private final ProtocolCommandOutboxService outboxService;

    public NormalGroupCreationCommandDispatcher(
            NormalGroupCreationMapper mapper,
            ProtocolCommandOutboxService outboxService) {
        this.mapper = mapper;
        this.outboxService = outboxService;
    }

    /** 为每个成员同时提交“建群人保存成员”和“成员保存建群人”两个单方向动作。 */
    public void enqueueContactPrepare(ItemWork item, List<MemberWork> members) {
        enqueueContactPrepare(item, members, false);
    }

    /** 人工重试只重新提交明确 FAILED 的联系人方向，成功方向保持不变。 */
    public void enqueueFailedContactPrepare(ItemWork item, List<MemberWork> members) {
        enqueueContactPrepare(item, members, true);
    }

    private void enqueueContactPrepare(
            ItemWork item, List<MemberWork> members, boolean failedOnly) {
        List<DirectedCommand> directed = new ArrayList<>(members.size() * 2);
        ProtocolAccountRef creator = account(
                item.creatorAccountId(), item.creatorProtocolBackend(),
                item.creatorProtocolAccountId(), item.creatorWsPhone());
        for (MemberWork member : members) {
            ProtocolAccountRef memberAccount = account(
                    member.memberAccountId(), member.memberProtocolBackend(),
                    member.memberProtocolAccountId(), member.memberWsPhone());
            validateRetryStatus(member.creatorSavedMemberStatus(), failedOnly);
            validateRetryStatus(member.memberSavedCreatorStatus(), failedOnly);
            if (!failedOnly || "FAILED".equals(member.creatorSavedMemberStatus())) {
                directed.add(new DirectedCommand(member.id(), "CREATOR_SAVE_MEMBER",
                        failedOnly ? "FAILED" : "PENDING",
                        request(item, member.id(), "CREATOR_SAVE_MEMBER", "CONTACT_PREPARE", creator)));
            }
            if (!failedOnly || "FAILED".equals(member.memberSavedCreatorStatus())) {
                directed.add(new DirectedCommand(member.id(), "MEMBER_SAVE_CREATOR",
                        failedOnly ? "FAILED" : "PENDING",
                        request(item, member.id(), "MEMBER_SAVE_CREATOR", "CONTACT_PREPARE", memberAccount)));
            }
        }
        if (directed.isEmpty()) {
            throw validation("没有明确失败的联系人方向可重试");
        }
        long now = System.currentTimeMillis();
        for (int from = 0; from < directed.size(); from += OUTBOX_BATCH_SIZE) {
            int to = Math.min(from + OUTBOX_BATCH_SIZE, directed.size());
            List<DirectedCommand> chunk = directed.subList(from, to);
            ProtocolCommandOutboxEnqueueResult result = outboxService.enqueueNormalGroupCreationCommands(
                    chunk.stream().map(DirectedCommand::request).toList());
            if (result.commandIds().size() != chunk.size()) {
                throw unavailable("联系人准备命令数量不一致");
            }
            for (int index = 0; index < chunk.size(); index++) {
                DirectedCommand row = chunk.get(index);
                if (mapper.bindContactCommand(
                        row.memberId(), row.direction(), row.expectedStatus(),
                        result.commandIds().get(index), now) != 1) {
                    throw unavailable("联系人准备命令关联失败");
                }
            }
        }
        if (mapper.markContactPrepareSubmitted(item.id(), now) != 1) {
            throw unavailable("联系人准备阶段提交失败");
        }
    }

    private static void validateRetryStatus(String status, boolean failedOnly) {
        if (failedOnly && !List.of("SUCCESS", "FAILED").contains(status)) {
            throw validation("联系人方向仍在处理中或结果未知，需先完成对账");
        }
    }

    /** 提交由建群账号执行的单个后续动作并返回真实 commandId。 */
    public String enqueueCreatorAction(ItemWork item, String action) {
        ProtocolAccountRef creator = account(
                item.creatorAccountId(), item.creatorProtocolBackend(),
                item.creatorProtocolAccountId(), item.creatorWsPhone());
        ProtocolCommandOutboxEnqueueResult result = outboxService.enqueueNormalGroupCreationCommands(
                List.of(request(item, null, null, action, creator)));
        if (result.commandIds().size() != 1) {
            throw unavailable(action + " 命令数量不一致");
        }
        return result.commandIds().get(0);
    }

    private static ProtocolNormalGroupCreationCommandRequest request(
            ItemWork item,
            Long memberId,
            String direction,
            String action,
            ProtocolAccountRef actor) {
        return new ProtocolNormalGroupCreationCommandRequest(
                item.tenantId(), item.taskId(), item.id(), memberId, direction, action, actor);
    }

    private static ProtocolAccountRef account(
            Long accountId,
            String backend,
            String protocolAccountId,
            String wsPhone) {
        return new ProtocolAccountRef(
                accountId,
                ProtocolBackend.valueOf(backend.toUpperCase(Locale.ROOT)),
                protocolAccountId,
                wsPhone);
    }

    private static BusinessException unavailable(String detail) {
        return new BusinessException(
                ErrorCode.AUTH_SERVICE_UNAVAILABLE, "新建普群协议命令提交失败：" + detail);
    }

    private static BusinessException validation(String detail) {
        return new BusinessException(ErrorCode.VALIDATION, detail);
    }

    private record DirectedCommand(
            Long memberId,
            String direction,
            String expectedStatus,
            ProtocolNormalGroupCreationCommandRequest request) {
    }
}
