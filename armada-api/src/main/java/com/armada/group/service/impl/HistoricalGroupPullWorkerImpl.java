package com.armada.group.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupAddStatus;
import com.armada.group.model.enums.HistoricalGroupContactStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import com.armada.group.service.HistoricalGroupPullProtocolPorts;
import com.armada.group.service.HistoricalGroupPullWorker;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 历史群一次性拉人 worker 实现。
 *
 * <p>Worker 在指定租户上下文内选择 Web 拉手账号，先加入目标群，再逐个预存联系人并按配置分批加人；
 * 每个阶段都把协议结果落入执行明细，最终由终态汇总器统一收口。</p>
 */
@Service
public class HistoricalGroupPullWorkerImpl implements HistoricalGroupPullWorker {

    /** 安全日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(HistoricalGroupPullWorkerImpl.class);

    /** 协议逐成员成功状态。 */
    private static final String PARTICIPANT_STATUS_OK = "OK";

    /** 协议缺少逐成员结果错误码。 */
    private static final String PARTICIPANT_RESULT_MISSING = "PROTOCOL_RESULT_MISSING";

    /** 无可用拉手错误码。 */
    private static final String PULLER_UNAVAILABLE = "PULLER_UNAVAILABLE";

    /** 无可用拉手完整提示。 */
    private static final String PULLER_UNAVAILABLE_MESSAGE =
            "拉手账号分组中没有在线正常且协议身份完整的账号";

    /** 拉手后端不受当前联系人和 ADD 链路支持的错误码。 */
    private static final String PULLER_BACKEND_UNSUPPORTED = "PULLER_BACKEND_UNSUPPORTED";

    /** 拉手后端不受支持的完整提示。 */
    private static final String PULLER_BACKEND_UNSUPPORTED_MESSAGE =
            "历史群联系人保存和成员添加当前只支持 Web 拉手账号";

    /** 错误码列最大字符数。 */
    private static final int ERROR_CODE_MAX_CHARS = 64;

    /** TEXT 错误信息按 utf8mb4 最坏情况预留后的最大字符数。 */
    private static final int ERROR_MESSAGE_MAX_CHARS = 16_000;

    /** 在线正常拉手选择服务。 */
    private final AccountProtocolLookupService accountLookupService;

    /** 执行聚合数据访问。 */
    private final HistoricalGroupPullExecutionMapper executionMapper;

    /** 成员明细数据访问。 */
    private final HistoricalGroupPullMemberMapper memberMapper;

    /** 拉人所需协议端口。 */
    private final HistoricalGroupPullProtocolPorts protocolPorts;

    /** 持久化终态汇总器。 */
    private final HistoricalGroupPullExecutionFinalizer finalizer;

    /**
     * 创建历史群一次性拉人 worker。
     *
     * @param accountLookupService 在线正常拉手选择服务
     * @param executionMapper      执行聚合数据访问
     * @param memberMapper         成员明细数据访问
     * @param protocolPorts        拉人协议端口组合
     * @param finalizer            持久化终态汇总器
     */
    public HistoricalGroupPullWorkerImpl(
            AccountProtocolLookupService accountLookupService,
            HistoricalGroupPullExecutionMapper executionMapper,
            HistoricalGroupPullMemberMapper memberMapper,
            HistoricalGroupPullProtocolPorts protocolPorts,
            HistoricalGroupPullExecutionFinalizer finalizer) {
        this.accountLookupService = accountLookupService;
        this.executionMapper = executionMapper;
        this.memberMapper = memberMapper;
        this.protocolPorts = protocolPorts;
        this.finalizer = finalizer;
    }

    /**
     * 显式传播租户上下文并执行一次已认领拉人任务。
     *
     * @param tenantId    执行所属租户 ID
     * @param executionId 已认领执行 ID
     */
    @Override
    public void execute(Long tenantId, Long executionId) {
        Long previousTenantId = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            HistoricalGroupPullExecution execution =
                    executionMapper.selectByTenantAndId(tenantId, executionId);
            if (execution == null
                    || execution.getPullStatus() != HistoricalGroupPullStatus.RUNNING.code()) {
                log.info("历史群拉人 worker 跳过非运行执行 executionId={}", executionId);
                return;
            }
            if (execution.getOwnerUserId() == null) {
                failUnownedExecution(executionId);
                return;
            }
            try (DataScopeContext.Scope ignored =
                         DataScopeContext.open(DataScope.self(execution.getOwnerUserId()))) {
                executeInTenant(execution);
            }
        } finally {
            restoreTenant(previousTenantId);
        }
    }

    /**
     * 在已建立的租户上下文中执行完整拉人流程。
     *
     * <p>只有运行中的执行可以继续；拉手选择和进群属于前置步骤，失败时会统一终结全部未完成成员。</p>
     *
     * @param execution 已按租户读取并验证归属的运行中执行
     */
    private void executeInTenant(HistoricalGroupPullExecution execution) {
        Long executionId = execution.getId();
        List<HistoricalGroupPullMember> members = memberMapper.selectOrderedByExecutionId(executionId);
        Optional<ProtocolAccountRef> selected =
                accountLookupService.findRandomOnlineNormalWebByGroupId(execution.getPullerAccountGroupId());
        if (selected.isEmpty()) {
            Optional<ProtocolAccountRef> anyBackend =
                    accountLookupService.findRandomOnlineNormalByGroupId(execution.getPullerAccountGroupId());
            if (anyBackend.isPresent() && anyBackend.get().backend() != ProtocolBackend.WEB) {
                finishFrontFailure(executionId, members, "PULLER_SELECT",
                        new Failure(PULLER_BACKEND_UNSUPPORTED, PULLER_BACKEND_UNSUPPORTED_MESSAGE));
                return;
            }
            finishFrontFailure(executionId, members, "PULLER_SELECT",
                    new Failure(PULLER_UNAVAILABLE, PULLER_UNAVAILABLE_MESSAGE));
            return;
        }
        ProtocolAccountRef puller = selected.get();
        if (puller.backend() != ProtocolBackend.WEB) {
            finishFrontFailure(executionId, members, "PULLER_SELECT",
                    new Failure(PULLER_BACKEND_UNSUPPORTED, PULLER_BACKEND_UNSUPPORTED_MESSAGE));
            return;
        }
        int assigned = executionMapper.assignPullerIfRunning(
                executionId, puller.armadaAccountId(), HistoricalGroupPullStatus.RUNNING.code(),
                System.currentTimeMillis());
        if (assigned != 1) {
            log.info("历史群拉人 worker 跳过已冻结执行 executionId={}", executionId);
            return;
        }
        if (!joinTargetGroup(execution, puller, members)) {
            return;
        }
        processContacts(puller, members);
        processAddBatches(execution, puller, members);
        finalizer.finish(executionId, null, null, null);
    }

    /** 历史 NULL owner 不能被后台线程解释为任意用户范围。 */
    private void failUnownedExecution(Long executionId) {
        long now = System.currentTimeMillis();
        HistoricalGroupPullExecution terminal = new HistoricalGroupPullExecution();
        terminal.setId(executionId);
        terminal.setPullStatus(HistoricalGroupPullStatus.FAILED.code());
        terminal.setFailureStage("DATA_SCOPE");
        terminal.setErrorCode("OWNER_USER_MISSING");
        terminal.setErrorMessage("历史无归属执行不能访问用户私有资源，请重新创建执行");
        terminal.setFinishedAt(now);
        terminal.setUpdatedAt(now);
        executionMapper.finishIfRunning(
                terminal, HistoricalGroupPullStatus.RUNNING.code());
    }

    /**
     * 使用选定拉手加入目标历史群。
     *
     * @param execution 当前拉人执行
     * @param puller 已选定的 Web 拉手协议账号
     * @param members 当前执行的全部成员明细
     * @return 协议确认已进群时返回 {@code true}；否则完成前置失败收口并返回 {@code false}
     */
    private boolean joinTargetGroup(
            HistoricalGroupPullExecution execution,
            ProtocolAccountRef puller,
            List<HistoricalGroupPullMember> members) {
        GroupJoinResult result;
        try {
            result = protocolPorts.groupJoin().join(new GroupJoinCommand(
                    puller,
                    execution.getInviteLink(),
                    "historical-group-pull:" + execution.getId()));
        } catch (RuntimeException ex) {
            Failure failure = failureOf(ex);
            log.warn("历史群拉手进群失败 executionId={} pullerAccountId={} errorCode={}",
                    execution.getId(), puller.armadaAccountId(), failure.code());
            finishFrontFailure(execution.getId(), members, "GROUP_JOIN", failure);
            return false;
        }
        if (result != null && result.joined()) {
            return true;
        }
        String outcome = result == null ? "NULL_RESULT" : result.outcome().name();
        Failure failure = new Failure(
                "GROUP_JOIN_REJECTED",
                "协议未确认拉手已进入目标群: " + outcome);
        finishFrontFailure(execution.getId(), members, "GROUP_JOIN", failure);
        return false;
    }

    /**
     * 为仍待处理的料子号码逐个预存联系人并记录真实结果。
     *
     * <p>联系人保存失败不阻止后续成员 ADD，两个结果维度分别落库。</p>
     *
     * @param puller 执行联系人保存的拉手协议账号
     * @param members 当前执行的全部成员明细
     */
    private void processContacts(ProtocolAccountRef puller, List<HistoricalGroupPullMember> members) {
        for (HistoricalGroupPullMember member : members) {
            if (member.getContactStatus() != HistoricalGroupContactStatus.PENDING.code()) {
                continue;
            }
            try {
                protocolPorts.contact().save(new ContactSaveCommand(
                        puller,
                        member.getPhone(),
                        member.getPhone(),
                        "historical-group-pull-member:" + member.getId()));
                updateContact(member.getId(), HistoricalGroupContactStatus.SUCCESS, null);
            } catch (RuntimeException ex) {
                Failure failure = failureOf(ex);
                log.warn("历史群联系人预存失败 executionId={} memberId={} errorCode={}",
                        member.getExecutionId(), member.getId(), failure.code());
                updateContact(member.getId(), HistoricalGroupContactStatus.FAILED, failure);
            }
        }
    }

    /**
     * 按执行配置把待加成员稳定切分为多个协议批次。
     *
     * @param execution 当前拉人执行，提供目标群及单批数量
     * @param puller 执行成员操作的拉手协议账号
     * @param members 当前执行的全部成员明细
     */
    private void processAddBatches(
            HistoricalGroupPullExecution execution,
            ProtocolAccountRef puller,
            List<HistoricalGroupPullMember> members) {
        List<HistoricalGroupPullMember> pending = members.stream()
                .filter(member -> member.getAddStatus() == HistoricalGroupAddStatus.PENDING.code())
                .toList();
        int batchSize = execution.getSingleAddCount();
        for (int start = 0; start < pending.size(); start += batchSize) {
            List<HistoricalGroupPullMember> batch =
                    pending.subList(start, Math.min(start + batchSize, pending.size()));
            addBatch(execution, puller, batch);
        }
    }

    /**
     * 下发一个成员 ADD 批次并把批次级异常转换为逐成员失败。
     *
     * @param execution 当前拉人执行
     * @param puller 执行成员操作的拉手协议账号
     * @param batch 本次待添加的成员明细
     */
    private void addBatch(
            HistoricalGroupPullExecution execution,
            ProtocolAccountRef puller,
            List<HistoricalGroupPullMember> batch) {
        List<String> participantJids = batch.stream()
                .map(HistoricalGroupPullWorkerImpl::participantJid)
                .toList();
        GroupParticipantBatchResult result;
        try {
            result = protocolPorts.participants().updateParticipants(
                    puller,
                    execution.getGroupJid(),
                    participantJids,
                    GroupParticipantAction.ADD);
        } catch (RuntimeException ex) {
            Failure failure = failureOf(ex);
            log.warn("历史群成员 ADD 批次失败 executionId={} batchSize={} errorCode={}",
                    execution.getId(), batch.size(), failure.code());
            batch.forEach(member -> updateAdd(member.getId(), HistoricalGroupAddStatus.FAILED, failure));
            return;
        }
        applyAddResults(batch, result);
    }

    /**
     * 将协议逐成员 ADD 结果按 JID 对齐到本地成员明细。
     *
     * <p>协议未返回某个成员结果时按失败处理，避免把批次外层成功误判为全部成员成功。</p>
     *
     * @param batch 本次下发的成员明细
     * @param result 协议返回的逐成员结果
     */
    private void applyAddResults(
            List<HistoricalGroupPullMember> batch,
            GroupParticipantBatchResult result) {
        Map<String, GroupParticipantBatchResult.Item> resultByJid = resultsByJid(result);
        for (HistoricalGroupPullMember member : batch) {
            GroupParticipantBatchResult.Item item = resultByJid.get(participantJid(member));
            if (item == null) {
                updateAdd(member.getId(), HistoricalGroupAddStatus.FAILED,
                        new Failure(PARTICIPANT_RESULT_MISSING, "协议未返回该成员 ADD 结果"));
                continue;
            }
            String status = trimToNull(item.status());
            if (PARTICIPANT_STATUS_OK.equals(status)) {
                updateAdd(member.getId(), HistoricalGroupAddStatus.SUCCESS, null);
                continue;
            }
            String code = status == null ? PARTICIPANT_RESULT_MISSING : status;
            String message = firstText(item.rawStatus(), status, "协议成员 ADD 结果缺少状态");
            updateAdd(member.getId(), HistoricalGroupAddStatus.FAILED, new Failure(code, message));
        }
    }

    /**
     * 收口拉手选择或进群阶段失败，冻结全部尚未完成的成员结果。
     *
     * @param executionId 当前执行 ID
     * @param members 当前执行的全部成员明细
     * @param failureStage 失败阶段
     * @param failure 已裁剪的失败快照
     */
    private void finishFrontFailure(
            Long executionId,
            List<HistoricalGroupPullMember> members,
            String failureStage,
            Failure failure) {
        for (HistoricalGroupPullMember member : members) {
            updateContact(member.getId(), HistoricalGroupContactStatus.FAILED, failure);
            updateAdd(member.getId(), HistoricalGroupAddStatus.FAILED, failure);
        }
        finalizer.finish(executionId, truncate(failureStage, ERROR_CODE_MAX_CHARS),
                failure.code(), failure.message());
    }

    /**
     * 仅在联系人状态仍为待处理时写入最终结果。
     *
     * @param memberId 成员明细 ID
     * @param status 联系人保存终态
     * @param failure 失败快照；成功时为空
     */
    private void updateContact(
            Long memberId,
            HistoricalGroupContactStatus status,
            Failure failure) {
        memberMapper.updateContactResultIfPending(
                memberId,
                HistoricalGroupContactStatus.PENDING.code(),
                status.code(),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message(),
                System.currentTimeMillis());
    }

    /**
     * 仅在加人状态仍为待处理时写入最终结果。
     *
     * @param memberId 成员明细 ID
     * @param status 成员 ADD 终态
     * @param failure 失败快照；成功时为空
     */
    private void updateAdd(Long memberId, HistoricalGroupAddStatus status, Failure failure) {
        memberMapper.updateAddResultIfPending(
                memberId,
                HistoricalGroupAddStatus.PENDING.code(),
                status.code(),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message(),
                System.currentTimeMillis());
    }

    /**
     * 将协议逐成员结果转换为按规范化 JID 首次命中的映射。
     *
     * @param result 协议批量成员操作结果，可空
     * @return JID 到逐成员结果的稳定映射
     */
    private static Map<String, GroupParticipantBatchResult.Item> resultsByJid(
            GroupParticipantBatchResult result) {
        Map<String, GroupParticipantBatchResult.Item> byJid = new LinkedHashMap<>();
        if (result == null || result.results() == null) {
            return byJid;
        }
        for (GroupParticipantBatchResult.Item item : result.results()) {
            if (item != null && trimToNull(item.jid()) != null) {
                byJid.putIfAbsent(item.jid().trim(), item);
            }
        }
        return byJid;
    }

    /**
     * 将协议异常或未知运行时异常转换为可持久化失败快照。
     *
     * @param ex 协议调用抛出的运行时异常
     * @return 包含稳定错误码和消息的失败快照
     */
    private static Failure failureOf(RuntimeException ex) {
        if (ex instanceof ProtocolException protocolException) {
            String code = protocolException.protocolCode()
                    .orElse(protocolException.errorCode().name());
            return new Failure(code, protocolException.getMessage());
        }
        return new Failure("UNEXPECTED_ERROR", ex.getMessage());
    }

    /**
     * 将成员手机号转换为 WhatsApp 用户 JID。
     *
     * @param member 成员明细
     * @return WhatsApp 用户 JID
     */
    private static String participantJid(HistoricalGroupPullMember member) {
        return WhatsappJids.userJid(member.getPhone());
    }

    /**
     * 按顺序选择第一个非空文本作为错误信息。
     *
     * @param values 候选文本
     * @return 第一个有效文本；全部为空时返回统一协议失败提示
     */
    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "协议层调用失败";
    }

    /**
     * 清理可选文本。
     *
     * @param value 待清理文本
     * @return 去除首尾空白后的文本；无有效内容时返回 {@code null}
     */
    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 按数据库列限制截断错误文本。
     *
     * @param value 原始错误文本
     * @param maxChars 最大字符数
     * @return 非空且不超过限制的错误文本
     */
    private static String truncate(String value, int maxChars) {
        String normalized = firstText(value);
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    /**
     * 恢复线程进入 worker 前的租户上下文，防止线程复用造成租户串扰。
     *
     * @param previousTenantId worker 执行前的租户 ID；为空表示原线程没有租户上下文
     */
    private static void restoreTenant(Long previousTenantId) {
        if (previousTenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenantId);
        }
    }

    /** 安全长度错误快照。 */
    private record Failure(String code, String message) {

        private Failure {
            code = truncate(code, ERROR_CODE_MAX_CHARS);
            message = truncate(message, ERROR_MESSAGE_MAX_CHARS);
        }
    }
}
