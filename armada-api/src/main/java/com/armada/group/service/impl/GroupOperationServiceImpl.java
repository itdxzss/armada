package com.armada.group.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.group.model.dto.GroupCreateDTO;
import com.armada.group.model.vo.GroupCreateParticipantVO;
import com.armada.group.model.vo.GroupCreateVO;
import com.armada.group.service.GroupOperationService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * WhatsApp 群真实操作服务实现。
 */
@Service
public class GroupOperationServiceImpl implements GroupOperationService {

    private static final int GROUP_SUBJECT_MAX_LENGTH = 100;
    private final AccountMapper accountMapper;
    private final GroupCreatePort groupCreatePort;

    public GroupOperationServiceImpl(AccountMapper accountMapper, GroupCreatePort groupCreatePort) {
        this.accountMapper = accountMapper;
        this.groupCreatePort = groupCreatePort;
    }

    @Override
    public GroupCreateVO createGroup(GroupCreateDTO dto) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "建群请求不能为空");
        }
        String subject = requireSubject(dto.subject());
        List<String> participants = requireParticipants(dto.participants());
        String protocolAccountId = resolveOnlineProtocolAccountId(dto.accountId());
        GroupCreateResult result;
        try {
            result = groupCreatePort.create(protocolAccountId, subject, participants);
        } catch (ProtocolException ex) {
            throw translateGroupCreateProtocolException(ex);
        }
        return new GroupCreateVO(
                result.groupJid(),
                result.partial(),
                result.results().stream()
                        .map(item -> new GroupCreateParticipantVO(item.jid(), item.status(), item.rawStatus()))
                        .toList());
    }

    private String resolveOnlineProtocolAccountId(Long accountId) {
        if (accountId == null || accountId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "操作账号 ID 不能为空");
        }
        Account account = accountMapper.selectActiveById(accountId);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账号不存在或已删除: " + accountId);
        }
        String protocolAccountId = account.getProtocolAccountId();
        if (protocolAccountId == null || protocolAccountId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号未绑定协议账号: " + accountId);
        }
        List<Long> onlineIds = accountMapper.selectOnlineAccountIdsByIds(List.of(accountId), AccountLoginStateCode.ONLINE);
        if (onlineIds == null || !onlineIds.contains(accountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "操作账号未在线: " + accountId);
        }
        return protocolAccountId;
    }

    private static RuntimeException translateGroupCreateProtocolException(ProtocolException ex) {
        if (ex.errorCode() == ProtocolErrorCode.ACCOUNT_BUSY) {
            return new BusinessException(ErrorCode.CONFLICT, busyMessage("账号群操作繁忙", ex));
        }
        if (ex.errorCode() == ProtocolErrorCode.WORKER_BUSY) {
            return new BusinessException(ErrorCode.CONFLICT, busyMessage("协议 worker 群操作繁忙", ex));
        }
        return ex;
    }

    private static String busyMessage(String prefix, ProtocolException ex) {
        return ex.retryAfterMs()
                .map(retryAfterMs -> prefix + ",请稍后重试(retryAfterMs=" + retryAfterMs + ")")
                .orElse(prefix + ",请稍后重试");
    }

    private static String requireSubject(String subject) {
        String normalized = subject == null ? null : subject.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群名称不能为空");
        }
        if (normalized.length() > GROUP_SUBJECT_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION, "群名称长度不能超过" + GROUP_SUBJECT_MAX_LENGTH);
        }
        return normalized;
    }

    private static List<String> requireParticipants(List<String> participants) {
        if (participants == null || participants.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "participants 不能为空");
        }
        if (participants.stream().anyMatch(item -> item == null || item.isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION, "participants 不能包含空值");
        }
        return participants.stream().map(String::trim).toList();
    }
}
