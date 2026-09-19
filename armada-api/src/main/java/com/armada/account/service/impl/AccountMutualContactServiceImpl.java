package com.armada.account.service.impl;

import com.armada.account.converter.AccountMutualContactConverter;
import com.armada.account.mapper.AccountMutualContactMapper;
import com.armada.account.model.dto.AccountMutualContactCandidate;
import com.armada.account.model.dto.AccountMutualContactCreateDTO;
import com.armada.account.model.dto.AccountMutualContactQuery;
import com.armada.account.model.entity.AccountMutualContactItem;
import com.armada.account.model.entity.AccountMutualContactTask;
import com.armada.account.model.enums.AccountMutualContactStatus;
import com.armada.account.model.enums.AccountMutualContactTaskStatus;
import com.armada.account.model.vo.AccountMutualContactGroupVO;
import com.armada.account.model.vo.AccountMutualContactItemVO;
import com.armada.account.model.vo.AccountMutualContactPreviewVO;
import com.armada.account.model.vo.AccountMutualContactTaskVO;
import com.armada.account.service.AccountMutualContactAccess;
import com.armada.account.service.AccountMutualContactPolicy;
import com.armada.account.service.AccountMutualContactService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

/** 预览冻结范围、任务创建与运营查询，协议动作由后台调度处理。 */
@Service
public class AccountMutualContactServiceImpl implements AccountMutualContactService {
    private static final int INSERT_BATCH = 200;
    private final AccountMutualContactMapper mapper;
    private final AccountMutualContactConverter converter;
    public AccountMutualContactServiceImpl(AccountMutualContactMapper mapper, AccountMutualContactConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    @Override
    public AccountMutualContactPreviewVO preview(AccountMutualContactCreateDTO req, AuthPrincipal p) {
        return plan(req, p).preview();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountMutualContactTaskVO create(AccountMutualContactCreateDTO req, AuthPrincipal p) {
        validate(req, p);
        if (req.requestId() == null || !req.requestId().matches("[A-Za-z0-9-]{16,64}"))
            throw invalid("创建请求幂等键无效");
        AccountMutualContactTask existing = mapper.byRequest(p.userId(), req.requestId());
        if (existing != null) {
            if (!Objects.equals(existing.getLeftGroupId(), req.leftGroupId())
                    || !Objects.equals(existing.getRightGroupId(), req.rightGroupId())
                    || !Objects.equals(existing.getIntervalSeconds(), req.intervalSeconds()))
                throw invalid("同一请求不能修改任务参数");
            return toVO(existing);
        }
        Plan plan = plan(req, p);
        AccountMutualContactPolicy.operationCount(plan.left().size(), plan.right().size());
        if (!Objects.equals(plan.preview().previewToken(), req.previewToken()))
            throw new BusinessException(ErrorCode.CONFLICT, "参与账号已变化，请重新预览");
        long now = System.currentTimeMillis();
        AccountMutualContactTask task = new AccountMutualContactTask();
        task.setTenantId(p.tenantId());
        task.setCreatedBy(p.userId());
        task.setRequestId(req.requestId());
        task.setLeftGroupId(req.leftGroupId());
        task.setRightGroupId(req.rightGroupId());
        task.setLeftGroupName(plan.preview().left().name());
        task.setRightGroupName(plan.preview().right().name());
        task.setLeftCount(plan.left().size());
        task.setRightCount(plan.right().size());
        task.setIntervalSeconds(req.intervalSeconds());
        task.setStatus(AccountMutualContactTaskStatus.RUNNING.code());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            mapper.insertTask(task);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "重复创建请求，请使用原请求重试查询");
        }
        List<AccountMutualContactItem> batch = new ArrayList<>();
        for (var left : plan.left()) {
            for (var right : plan.right()) {
                batch.add(item(task, left, right, now));
                batch.add(item(task, right, left, now));
                if (batch.size() >= INSERT_BATCH) {
                    mapper.insertItems(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty())
            mapper.insertItems(batch);
        return toVO(task);
    }

    @Override
    public PageResult<AccountMutualContactTaskVO> list(AccountMutualContactQuery q, AuthPrincipal p) {
        AccountMutualContactAccess.require(p);
        Long owner = AccountMutualContactAccess.admin(p) ? null : p.userId();
        return PageResult.of(mapper.tasks(owner, q).stream().map(this::toVO).toList(), q.getPage(), q.getPageSize(),
                mapper.countTasks(owner));
    }
    @Override
    public AccountMutualContactTaskVO detail(Long id, AuthPrincipal p) {
        return toVO(requireTask(mapper.task(id), p));
    }
    @Override
    public PageResult<AccountMutualContactItemVO> items(Long id, AccountMutualContactQuery q, AuthPrincipal p) {
        requireTask(mapper.task(id), p);
        var rows = converter.items(mapper.items(id, q));
        return PageResult.of(rows, q.getPage(), q.getPageSize(), mapper.countItems(id, q));
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stop(Long id, AuthPrincipal p) {
        requireTask(mapper.lockTask(id), p);
        long now = System.currentTimeMillis();
        mapper.setTaskStatus(id, AccountMutualContactTaskStatus.STOPPED.code(), now);
        mapper.cancelPending(id, now);
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int retry(Long id, AuthPrincipal p) {
        requireTask(mapper.lockTask(id), p);
        long now = System.currentTimeMillis();
        int count = mapper.retryFailed(id, now);
        if (count > 0)
            mapper.setTaskStatus(id, AccountMutualContactTaskStatus.RUNNING.code(), now);
        return count;
    }
    private Plan plan(AccountMutualContactCreateDTO req, AuthPrincipal p) {
        validate(req, p);
        var leftGroup = mapper.group(req.leftGroupId());
        var rightGroup = mapper.group(req.rightGroupId());
        if (leftGroup == null || rightGroup == null)
            throw invalid("账号分组不存在或无权访问");
        var identities = new HashSet<String>();
        var left = select(req.leftGroupId(), p, identities);
        var right = select(req.rightGroupId(), p, identities);
        long leftTotal = mapper.groupCount(req.leftGroupId()), rightTotal = mapper.groupCount(req.rightGroupId());
        long operationCount = 2L * left.size() * right.size();
        if (operationCount > AccountMutualContactPolicy.MAX_OPERATIONS)
            throw invalid("本次定向保存超过 20000 次，请缩小分组");
        String material = p.tenantId() + ":" + p.userId() + ":" + req.leftGroupId() + ":" + req.rightGroupId() + ":"
                + req.intervalSeconds() + ":" + left + ":" + right;
        String token = DigestUtils.md5DigestAsHex(material.getBytes(StandardCharsets.UTF_8));
        return new Plan(left, right,
                new AccountMutualContactPreviewVO(
                        new AccountMutualContactGroupVO(leftGroup.getId(), leftGroup.getName(), leftTotal, left.size(),
                                leftTotal - left.size()),
                        new AccountMutualContactGroupVO(rightGroup.getId(), rightGroup.getName(), rightTotal,
                                right.size(), rightTotal - right.size()),
                        (int) operationCount / 2, (int) operationCount, token));
    }
    private List<AccountMutualContactCandidate> select(Long groupId, AuthPrincipal p, HashSet<String> seen) {
        var rows = mapper.candidates(groupId, AccountMutualContactPolicy.MAX_OPERATIONS / 2 + 1);
        if (rows.size() > AccountMutualContactPolicy.MAX_OPERATIONS / 2)
            throw invalid("单组在线候选过多，请缩小分组");
        return rows.stream()
                .filter(AccountMutualContactAccess::eligible)
                .filter(a -> AccountMutualContactAccess.owns(p, a))
                .filter(a -> seen.add(a.wsPhone()))
                .toList();
    }
    private static void validate(AccountMutualContactCreateDTO req, AuthPrincipal p) {
        AccountMutualContactAccess.require(p);
        if (req == null || req.leftGroupId() == null || req.rightGroupId() == null || req.leftGroupId() <= 0
                || req.rightGroupId() <= 0 || req.leftGroupId().equals(req.rightGroupId())
                || req.intervalSeconds() == null)
            throw invalid("请选择两个不同分组并填写保存间隔");
        AccountMutualContactPolicy.nextAt(0, req.intervalSeconds());
    }
    private static AccountMutualContactTask requireTask(AccountMutualContactTask task, AuthPrincipal p) {
        AccountMutualContactAccess.require(p);
        if (task == null || (!AccountMutualContactAccess.admin(p) && !Objects.equals(task.getCreatedBy(), p.userId())))
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或无权访问");
        return task;
    }
    private AccountMutualContactTaskVO toVO(AccountMutualContactTask t) {
        return converter.task(t, mapper.stats(t.getId()));
    }
    private static AccountMutualContactItem item(AccountMutualContactTask task, AccountMutualContactCandidate actor,
            AccountMutualContactCandidate target, long now) {
        var i = new AccountMutualContactItem();
        i.setTenantId(task.getTenantId());
        i.setTaskId(task.getId());
        i.setActorId(actor.id());
        i.setTargetId(target.id());
        i.setActorPhone(actor.wsPhone());
        i.setTargetPhone(target.wsPhone());
        i.setProtocolAccountId(actor.protocolAccountId());
        i.setProtocolBackend(actor.protocolBackend());
        i.setStatus(AccountMutualContactStatus.PENDING.code());
        i.setCreatedAt(now);
        i.setUpdatedAt(now);
        return i;
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
    private record Plan(List<AccountMutualContactCandidate> left, List<AccountMutualContactCandidate> right,
            AccountMutualContactPreviewVO preview) {}
}
