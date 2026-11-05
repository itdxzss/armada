package com.armada.account.service.impl;

import com.armada.account.converter.AccountMutualChatConverter;
import com.armada.account.mapper.AccountMutualChatMapper;
import com.armada.account.model.dto.AccountMutualContactCandidate;
import com.armada.account.model.dto.AccountMutualChatCreateDTO;
import com.armada.account.model.dto.AccountMutualChatQuery;
import com.armada.account.model.entity.AccountMutualChatItem;
import com.armada.account.model.entity.AccountMutualChatTask;
import com.armada.account.model.enums.AccountMutualChatStatus;
import com.armada.account.model.enums.AccountMutualChatDirection;
import com.armada.account.model.enums.AccountMutualChatTaskStatus;
import com.armada.account.model.vo.AccountMutualChatItemVO;
import com.armada.account.model.vo.AccountMutualContactPreviewVO;
import com.armada.account.model.vo.AccountMutualChatTaskVO;
import com.armada.account.service.AccountMutualContactAccess;
import com.armada.account.service.AccountMutualChatPolicy;
import com.armada.account.service.AccountMutualGroupPlanner;
import com.armada.account.service.AccountMutualChatService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 预览冻结范围、任务创建与运营查询，协议动作由后台调度处理。 */
@Service
public class AccountMutualChatServiceImpl implements AccountMutualChatService {
    private static final int INSERT_BATCH = 200;
    private final AccountMutualChatMapper mapper;
    private final AccountMutualChatConverter converter;
    private final AccountMutualGroupPlanner planner;
    /** 使用共享分组预览固定参与身份，互聊任务事实独立持久化。 */
    public AccountMutualChatServiceImpl(AccountMutualChatMapper mapper, AccountMutualChatConverter converter,
            AccountMutualGroupPlanner planner) {
        this.mapper = mapper;
        this.converter = converter;
        this.planner = planner;
    }

    @Override
    public AccountMutualContactPreviewVO preview(AccountMutualChatCreateDTO req, AuthPrincipal p) {
        return plan(req, p).preview();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountMutualChatTaskVO create(AccountMutualChatCreateDTO req, AuthPrincipal p) {
        validate(req, p);
        if (req.requestId() == null || !req.requestId().matches("[A-Za-z0-9-]{16,64}"))
            throw invalid("创建请求幂等键无效");
        AccountMutualChatTask existing = mapper.byRequest(p.userId(), req.requestId());
        if (existing != null) {
            if (!Objects.equals(existing.getLeftGroupId(), req.leftGroupId())
                    || !Objects.equals(existing.getRightGroupId(), req.rightGroupId())
                    || !Objects.equals(existing.getIntervalSeconds(), req.intervalSeconds())
                    || !Objects.equals(existing.getLeftText(), AccountMutualChatPolicy.text(req.leftText()))
                    || !Objects.equals(existing.getRightText(), AccountMutualChatPolicy.text(req.rightText())))
                throw invalid("同一请求不能修改任务参数");
            return toVO(existing);
        }
        var plan = plan(req, p);
        AccountMutualChatPolicy.operationCount(plan.left().size(), plan.right().size());
        if (!Objects.equals(plan.preview().previewToken(), req.previewToken()))
            throw new BusinessException(ErrorCode.CONFLICT, "参与账号已变化，请重新预览");
        long now = System.currentTimeMillis();
        AccountMutualChatTask task = new AccountMutualChatTask();
        task.setTenantId(p.tenantId());
        task.setCreatedBy(p.userId());
        task.setRequestId(req.requestId());
        task.setLeftGroupId(req.leftGroupId());
        task.setRightGroupId(req.rightGroupId());
        task.setLeftGroupName(plan.preview().left().name());
        task.setRightGroupName(plan.preview().right().name());
        task.setLeftCount(plan.left().size());
        task.setRightCount(plan.right().size());
        task.setLeftText(AccountMutualChatPolicy.text(req.leftText()));
        task.setRightText(AccountMutualChatPolicy.text(req.rightText()));
        task.setIntervalSeconds(req.intervalSeconds());
        task.setStatus(AccountMutualChatTaskStatus.RUNNING.code());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            mapper.insertTask(task);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "重复创建请求，请使用原请求重试查询");
        }
        List<AccountMutualChatItem> batch = new ArrayList<>();
        for (var left : plan.left()) {
            for (var right : plan.right()) {
                batch.add(item(task, left, right, AccountMutualChatDirection.LEFT_TO_RIGHT, now));
                batch.add(item(task, right, left, AccountMutualChatDirection.RIGHT_TO_LEFT, now));
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
    public PageResult<AccountMutualChatTaskVO> list(AccountMutualChatQuery q, AuthPrincipal p) {
        AccountMutualContactAccess.require(p);
        Long owner = AccountMutualContactAccess.admin(p) ? null : p.userId();
        return PageResult.of(mapper.tasks(owner, q).stream().map(this::toVO).toList(), q.getPage(), q.getPageSize(),
                mapper.countTasks(owner));
    }
    @Override
    public AccountMutualChatTaskVO detail(Long id, AuthPrincipal p) {
        return toVO(requireTask(mapper.task(id), p));
    }
    @Override
    public PageResult<AccountMutualChatItemVO> items(Long id, AccountMutualChatQuery q, AuthPrincipal p) {
        requireTask(mapper.task(id), p);
        var rows = converter.items(mapper.items(id, q));
        return PageResult.of(rows, q.getPage(), q.getPageSize(), mapper.countItems(id, q));
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stop(Long id, AuthPrincipal p) {
        requireTask(mapper.lockTask(id), p);
        long now = System.currentTimeMillis();
        mapper.setTaskStatus(id, AccountMutualChatTaskStatus.STOPPED.code(), now);
        mapper.cancelPending(id, now);
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int retry(Long id, boolean confirmUnknown, AuthPrincipal p) {
        requireTask(mapper.lockTask(id), p);
        if (!confirmUnknown && mapper.stats(id).unknown() > 0)
            throw new BusinessException(ErrorCode.CONFLICT, "任务含待确认消息，请确认重复发送风险后重试");
        long now = System.currentTimeMillis();
        int count = mapper.retryFailed(id, now);
        if (count > 0)
            mapper.setTaskStatus(id, AccountMutualChatTaskStatus.RUNNING.code(), now);
        return count;
    }
    private AccountMutualGroupPlanner.Plan plan(AccountMutualChatCreateDTO req, AuthPrincipal p) {
        validate(req, p);
        return planner.plan(new AccountMutualGroupPlanner.Request(req.leftGroupId(), req.rightGroupId(),
                req.intervalSeconds(), AccountMutualChatPolicy.MAX_OPERATIONS), p);
    }
    private static void validate(AccountMutualChatCreateDTO req, AuthPrincipal p) {
        AccountMutualContactAccess.require(p);
        if (req == null || req.leftGroupId() == null || req.rightGroupId() == null || req.leftGroupId() <= 0
                || req.rightGroupId() <= 0 || req.leftGroupId().equals(req.rightGroupId())
                || req.intervalSeconds() == null)
            throw invalid("请选择两个不同分组并填写发送间隔");
        AccountMutualChatPolicy.nextAt(0, req.intervalSeconds());
        AccountMutualChatPolicy.text(req.leftText());
        AccountMutualChatPolicy.text(req.rightText());
    }
    private static AccountMutualChatTask requireTask(AccountMutualChatTask task, AuthPrincipal p) {
        AccountMutualContactAccess.require(p);
        if (task == null || (!AccountMutualContactAccess.admin(p) && !Objects.equals(task.getCreatedBy(), p.userId())))
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或无权访问");
        return task;
    }
    private AccountMutualChatTaskVO toVO(AccountMutualChatTask t) {
        return converter.task(t, mapper.stats(t.getId()));
    }
    private static AccountMutualChatItem item(AccountMutualChatTask task, AccountMutualContactCandidate actor,
            AccountMutualContactCandidate target, AccountMutualChatDirection direction, long now) {
        var i = new AccountMutualChatItem();
        i.setTenantId(task.getTenantId());
        i.setTaskId(task.getId());
        i.setActorId(actor.id());
        i.setTargetId(target.id());
        i.setActorPhone(actor.wsPhone());
        i.setTargetPhone(target.wsPhone());
        i.setProtocolAccountId(actor.protocolAccountId());
        i.setProtocolBackend(actor.protocolBackend());
        i.setDirection(direction.code());
        i.setStatus(AccountMutualChatStatus.PENDING.code());
        i.setCreatedAt(now);
        i.setUpdatedAt(now);
        return i;
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
