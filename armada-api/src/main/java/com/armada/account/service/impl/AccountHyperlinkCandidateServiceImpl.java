package com.armada.account.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountHyperlinkCandidateQuery;
import com.armada.account.model.vo.AccountHyperlinkCandidateVO;
import com.armada.account.service.AccountHyperlinkCandidateService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 通过账号域 Mapper 执行超链候选查询和账号行锁，封装跨域数据访问。 */
@Service
public class AccountHyperlinkCandidateServiceImpl implements AccountHyperlinkCandidateService {

    private static final int MAX_QUERY_LIMIT = 10_000;

    private final AccountMapper accountMapper;

    public AccountHyperlinkCandidateServiceImpl(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    @Override
    public List<AccountHyperlinkCandidateVO> selectCandidates(
            AccountHyperlinkCandidateQuery query, Integer afterPriority,
            Long afterAccountId, int limit) {
        long tenantId = validateQuery(query);
        boolean partialCursor = (afterPriority == null) != (afterAccountId == null);
        if (partialCursor || (afterAccountId != null && afterAccountId < 1)
                || limit < 1 || limit > MAX_QUERY_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链账号候选查询参数非法");
        }
        return List.copyOf(accountMapper.selectHyperlinkCandidates(
                tenantId, query, afterPriority, afterAccountId, limit));
    }

    private long validateQuery(AccountHyperlinkCandidateQuery query) {
        Long tenantId = TenantContext.get();
        if (tenantId == null || query == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链账号候选查询参数非法");
        }
        if (query.privateCapableBackends() == null
                || query.privateCapableBackends().isEmpty()
                || !Set.of("WEB", "ANDROID").containsAll(query.privateCapableBackends())) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链账号 PRIVATE 后端条件非法");
        }
        return tenantId;
    }

    @Override
    public int countCandidates(AccountHyperlinkCandidateQuery query) {
        return accountMapper.countHyperlinkCandidates(validateQuery(query), query);
    }

    @Override
    public List<String> listProtocolIds(List<String> privateCapableBackends) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链协议选项查询参数非法");
        }
        requirePrivateCapableBackends(privateCapableBackends, "超链协议选项查询参数非法");
        return List.copyOf(accountMapper.selectHyperlinkProtocolIds(
                tenantId, privateCapableBackends));
    }

    @Override
    public int countProtocols(List<String> privateCapableBackends) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链协议容量查询参数非法");
        }
        requirePrivateCapableBackends(privateCapableBackends, "超链协议容量查询参数非法");
        return accountMapper.countHyperlinkProtocols(tenantId, privateCapableBackends);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockForHyperlinkDispatch(long accountId) {
        Long tenantId = TenantContext.get();
        if (tenantId == null || accountId < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链账号派发锁参数非法");
        }
        return accountMapper.lockActiveForHyperlinkDispatch(tenantId, accountId) != null;
    }

    private void requirePrivateCapableBackends(List<String> backends, String message) {
        if (backends == null || backends.isEmpty()
                || !Set.of("WEB", "ANDROID").containsAll(backends)) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
    }
}
