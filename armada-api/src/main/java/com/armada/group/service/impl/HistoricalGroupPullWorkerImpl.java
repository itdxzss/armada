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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 历史群一次性拉人 worker 实现。 */
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
            executeInTenant(tenantId, executionId);
        } finally {
            restoreTenant(previousTenantId);
        }
    }

    private void executeInTenant(Long tenantId, Long executionId) {
        HistoricalGroupPullExecution execution = executionMapper.selectByTenantAndId(tenantId, executionId);
        if (execution == null || execution.getPullStatus() != HistoricalGroupPullStatus.RUNNING.code()) {
            log.info("历史群拉人 worker 跳过非运行执行 executionId={}", executionId);
            return;
        }
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
        processAddBatches(execution, puller.protocolAccountId(), members);
        finalizer.finish(executionId, null, null, null);
    }

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

    private void processAddBatches(
            HistoricalGroupPullExecution execution,
            String protocolAccountId,
            List<HistoricalGroupPullMember> members) {
        List<HistoricalGroupPullMember> pending = members.stream()
                .filter(member -> member.getAddStatus() == HistoricalGroupAddStatus.PENDING.code())
                .toList();
        int batchSize = execution.getSingleAddCount();
        for (int start = 0; start < pending.size(); start += batchSize) {
            List<HistoricalGroupPullMember> batch =
                    pending.subList(start, Math.min(start + batchSize, pending.size()));
            addBatch(execution, protocolAccountId, batch);
        }
    }

    private void addBatch(
            HistoricalGroupPullExecution execution,
            String protocolAccountId,
            List<HistoricalGroupPullMember> batch) {
        List<String> participantJids = batch.stream()
                .map(HistoricalGroupPullWorkerImpl::participantJid)
                .toList();
        GroupParticipantBatchResult result;
        try {
            result = protocolPorts.participants().updateParticipants(
                    protocolAccountId,
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

    private void updateAdd(Long memberId, HistoricalGroupAddStatus status, Failure failure) {
        memberMapper.updateAddResultIfPending(
                memberId,
                HistoricalGroupAddStatus.PENDING.code(),
                status.code(),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message(),
                System.currentTimeMillis());
    }

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

    private static Failure failureOf(RuntimeException ex) {
        if (ex instanceof ProtocolException protocolException) {
            String code = protocolException.protocolCode()
                    .orElse(protocolException.errorCode().name());
            return new Failure(code, protocolException.getMessage());
        }
        return new Failure("UNEXPECTED_ERROR", ex.getMessage());
    }

    private static String participantJid(HistoricalGroupPullMember member) {
        return WhatsappJids.userJid(member.getPhone());
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "协议层调用失败";
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int maxChars) {
        String normalized = firstText(value);
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

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
