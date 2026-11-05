package com.armada.account.service.impl;

import com.armada.account.converter.AccountTierCheckConverter;
import com.armada.account.mapper.AccountTierCheckMapper;
import com.armada.account.model.dto.AccountTierCheckCandidate;
import com.armada.account.model.dto.AccountTierCheckQuery;
import com.armada.account.model.dto.AccountTierCheckRequestDTO;
import com.armada.account.model.entity.AccountTierCheckItem;
import com.armada.account.model.entity.AccountTierCheckTask;
import com.armada.account.model.enums.AccountTierCheckState;
import com.armada.account.model.enums.AccountTypeCode;
import com.armada.account.model.vo.AccountTierCheckHistoryVO;
import com.armada.account.model.vo.AccountTierCheckItemVO;
import com.armada.account.model.vo.AccountTierCheckPreviewVO;
import com.armada.account.model.vo.AccountTierCheckTaskVO;
import com.armada.account.service.AccountTierCheckPolicy;
import com.armada.account.service.AccountTierCheckService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.AccountIntegrityPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只负责预览、批次冻结和运营读取，不在用户请求中发真实查询。 */
@Service
public class AccountTierCheckServiceImpl implements AccountTierCheckService {
    private static final int INSERT_BATCH = 200;
    private final AccountTierCheckMapper mapper;
    private final AccountTierCheckConverter converter;
    private final AccountIntegrityPort port;
    /** 注入本域持久层、编译期映射和只读协议能力。 */
    public AccountTierCheckServiceImpl(AccountTierCheckMapper mapper, AccountTierCheckConverter converter,
            AccountIntegrityPort port) {
        this.mapper = mapper;
        this.converter = converter;
        this.port = port;
    }
    @Override public AccountTierCheckPreviewVO preview(AccountTierCheckRequestDTO req, AuthPrincipal p) {
        return plan(normalize(req, p), p).preview();
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountTierCheckTaskVO create(AccountTierCheckRequestDTO input, AuthPrincipal p) {
        var req = normalize(input, p);
        if (req.requestId() == null || !req.requestId().matches("[A-Za-z0-9-]{16,64}"))
            throw AccountTierCheckPolicy.invalid("创建请求幂等键无效");
        String fingerprint = requestFingerprint(req);
        var existing = mapper.byRequest(p.userId(), req.requestId());
        if (existing != null) {
            if (!fingerprint.equals(existing.getRequestFingerprint()))
                throw new BusinessException(ErrorCode.CONFLICT, "同一请求不能修改检测参数");
            return toVO(existing);
        }
        Plan plan = plan(req, p);
        if (!Objects.equals(req.previewToken(), plan.preview().previewToken()))
            throw new BusinessException(ErrorCode.CONFLICT, "账号范围或状态已变化，请重新预览");
        if (plan.targets().isEmpty()) throw AccountTierCheckPolicy.invalid("没有可检测账号");
        long now = System.currentTimeMillis();
        var task = newTask(req, p, fingerprint, now);
        task.setTotalCount(plan.targets().size());
        insert(task);
        var batch = new ArrayList<AccountTierCheckItem>();
        for (var a : plan.targets()) {
            batch.add(newItem(task, a, now));
            if (batch.size() == INSERT_BATCH) { mapper.insertItems(batch); batch.clear(); }
        }
        if (!batch.isEmpty()) mapper.insertItems(batch);
        return toVO(task);
    }
    @Override public PageResult<AccountTierCheckTaskVO> list(AccountTierCheckQuery q, AuthPrincipal p) {
        AccountTierCheckPolicy.require(p);
        Long owner = AccountTierCheckPolicy.admin(p) ? null : p.userId();
        return PageResult.of(mapper.tasks(owner, q).stream().map(this::toVO).toList(),
                q.getPage(), q.getPageSize(), mapper.countTasks(owner, q));
    }
    @Override public AccountTierCheckTaskVO detail(Long id, AuthPrincipal p) {
        return toVO(requireTask(mapper.task(id), p));
    }
    @Override public PageResult<AccountTierCheckItemVO> items(Long id, AccountTierCheckQuery q, AuthPrincipal p) {
        requireTask(mapper.task(id), p);
        if (q.getFilter() != null && !q.getFilter().isEmpty()
                && !List.of("changed", "lost").contains(q.getFilter())) throw AccountTierCheckPolicy.invalid("明细筛选无效");
        return PageResult.of(converter.items(mapper.items(id, q)), q.getPage(), q.getPageSize(), mapper.countItems(id, q));
    }
    @Override @Transactional(rollbackFor = Exception.class)
    public void stop(Long id, AuthPrincipal p) {
        var task = requireTask(mapper.lockTask(id), p);
        if (task.getStatus() != AccountTierCheckState.RUNNING) return;
        long now = System.currentTimeMillis();
        mapper.cancelPending(id, now);
        mapper.setTaskStatus(id, AccountTierCheckState.STOPPED, now);
    }
    @Override @Transactional(rollbackFor = Exception.class)
    public int retry(Long id, AuthPrincipal p) {
        requireTask(mapper.lockTask(id), p);
        int count = mapper.retryFailed(id, System.currentTimeMillis());
        if (count > 0) mapper.setTaskStatus(id, AccountTierCheckState.RUNNING, System.currentTimeMillis());
        return count;
    }
    @Override @Transactional(rollbackFor = Exception.class)
    public AccountTierCheckTaskVO recheck(Long id, AuthPrincipal p) {
        var source = requireTask(mapper.lockTask(id), p);
        var ids = mapper.accountIds(id);
        var req = new AccountTierCheckRequestDTO(List.of(), ids, true, source.getMode(),
                AccountTierCheckPolicy.probes(source.getProbeAccountIds()), source.getName(), id,
                UUID.randomUUID().toString(), null);
        validateProbes(req, p);
        long now = System.currentTimeMillis();
        var task = newTask(req, p, requestFingerprint(req), now);
        task.setTotalCount(ids.size());
        insert(task);
        // 固定同一批账号，包括后来离线/删除的账号；由调度记失败，不能静默缩小对比样本。
        copyRecheckItems(source, task, req, now);
        return toVO(task);
    }
    @Override public AccountTierCheckHistoryVO history(Long accountId, AuthPrincipal p) {
        AccountTierCheckPolicy.require(p);
        var a = mapper.account(accountId);
        if (!AccountTierCheckPolicy.owns(p, a)) throw new BusinessException(ErrorCode.NOT_FOUND, "账号不存在或无权访问");
        var entries = mapper.history(accountId, AccountTierCheckPolicy.admin(p) ? null : p.userId());
        var latest = entries.stream().filter(e -> e.status() == AccountTierCheckState.SUCCESS).findFirst()
                .map(e -> mapper.item(e.itemId()));
        return new AccountTierCheckHistoryVO(accountId, a.wsPhone(), latest.map(AccountTierCheckItem::getServerUserType).orElse(null),
                latest.map(AccountTierCheckItem::getPhoneCountryCode).orElse(null), latest.map(AccountTierCheckItem::getJoinDateMs).orElse(null),
                latest.map(AccountTierCheckItem::getIsMvSubscribed).orElse(null), entries);
    }
    private Plan plan(AccountTierCheckRequestDTO req, AuthPrincipal p) {
        if (!req.accountGroupIds().isEmpty() && mapper.countGroups(req.accountGroupIds()) != req.accountGroupIds().size())
            throw AccountTierCheckPolicy.invalid("账号分组不存在或无权访问");
        if (req.sourceTaskId() != null) requireTask(mapper.task(req.sourceTaskId()), p);
        var probes = validateProbes(req, p);
        var rows = mapper.candidates(req, AccountTierCheckPolicy.MAX_ACCOUNTS + 1);
        if (rows.size() > AccountTierCheckPolicy.MAX_ACCOUNTS) throw AccountTierCheckPolicy.invalid("单批最多 20000 个账号，请缩小范围");
        var targets = new ArrayList<AccountTierCheckCandidate>();
        var reasons = new LinkedHashMap<String, Integer>();
        int missing = req.accountIds().isEmpty() ? 0 : req.accountIds().size() - rows.size();
        if (missing > 0) reasons.put("账号不存在或无权访问", missing);
        for (var a : rows) {
            String reason = excluded(a, req, p);
            if (reason.isEmpty()) targets.add(a); else reasons.merge(reason, 1, Integer::sum);
        }
        String material = p.tenantId() + ":" + p.userId() + ":" + requestFingerprint(req) + ":" + rows + ":" + probes;
        var excluded = reasons.entrySet().stream().map(e -> new AccountTierCheckPreviewVO.ExcludedReason(e.getKey(), e.getValue())).toList();
        return new Plan(targets, new AccountTierCheckPreviewVO(targets.size(), rows.size() + missing - targets.size(),
                excluded, AccountTierCheckPolicy.hash(material)));
    }
    private String excluded(AccountTierCheckCandidate a, AccountTierCheckRequestDTO req, AuthPrincipal p) {
        if (!AccountTierCheckPolicy.owns(p, a)) return "账号不存在或无权访问";
        if (!req.includePersonal() && !Integer.valueOf(AccountTypeCode.BUSINESS).equals(a.accountType())) return "未包含个人号";
        if (a.wsPhone() == null || !a.wsPhone().matches("[0-9]{5,20}")) return "账号号码无效";
        if (req.mode() == AccountTierCheckState.PROBE) return "";
        if (!port.supports(ProtocolBackend.valueOf(a.protocolBackend()))) return "协议暂不支持";
        if (!AccountTierCheckPolicy.online(a)) return "发起账号离线";
        if (a.protocolAccountId() == null || a.protocolAccountId().isBlank()) return "协议身份缺失";
        return "";
    }
    private List<AccountTierCheckCandidate> validateProbes(AccountTierCheckRequestDTO req, AuthPrincipal p) {
        if (req.mode() == AccountTierCheckState.SELF) return List.of();
        var probes = new ArrayList<AccountTierCheckCandidate>();
        for (Long id : req.probeAccountIds()) {
            var a = mapper.account(id);
            if (!AccountTierCheckPolicy.owns(p, a) || !AccountTierCheckPolicy.online(a))
                throw AccountTierCheckPolicy.invalid("探针必须属于当前可操作账号且在线");
            if (!port.supports(ProtocolBackend.valueOf(a.protocolBackend())) || a.protocolAccountId() == null
                    || a.protocolAccountId().isBlank() || a.wsPhone() == null || a.wsPhone().isBlank())
                throw AccountTierCheckPolicy.invalid("探针协议暂不支持或身份缺失");
            probes.add(a);
        }
        return probes;
    }
    private AccountTierCheckRequestDTO normalize(AccountTierCheckRequestDTO req, AuthPrincipal p) {
        AccountTierCheckPolicy.require(p);
        if (req == null || req.mode() == null || !List.of(AccountTierCheckState.SELF, AccountTierCheckState.PROBE).contains(req.mode()))
            throw AccountTierCheckPolicy.invalid("请选择有效检测方式");
        var groups = ids(req.accountGroupIds()); var accounts = ids(req.accountIds()); var probes = ids(req.probeAccountIds());
        if (groups.isEmpty() == accounts.isEmpty()) throw AccountTierCheckPolicy.invalid("勾选账号和账号分组必须二选一");
        if (req.mode() == AccountTierCheckState.PROBE && probes.isEmpty()) throw AccountTierCheckPolicy.invalid("请选择在线探针号");
        if (req.mode() == AccountTierCheckState.SELF && !probes.isEmpty()) throw AccountTierCheckPolicy.invalid("自查方式不使用探针号");
        String name = req.name() == null ? "" : req.name().trim();
        if (name.length() > 100) throw AccountTierCheckPolicy.invalid("批次名称最多 100 字");
        return new AccountTierCheckRequestDTO(groups, accounts, req.includePersonal(), req.mode(), probes, name,
                req.sourceTaskId(), req.requestId(), req.previewToken());
    }
    private static List<Long> ids(List<Long> values) {
        if (values == null) return List.of();
        if (values.size() > AccountTierCheckPolicy.MAX_ACCOUNTS || values.stream().anyMatch(v -> v == null || v <= 0))
            throw AccountTierCheckPolicy.invalid("账号或分组范围无效");
        return values.stream().distinct().sorted().toList();
    }
    private static String requestFingerprint(AccountTierCheckRequestDTO r) {
        return AccountTierCheckPolicy.hash(r.accountGroupIds() + ":" + r.accountIds() + ":" + r.includePersonal()
                + ":" + r.mode() + ":" + r.probeAccountIds() + ":" + r.name() + ":" + r.sourceTaskId());
    }
    private void copyRecheckItems(AccountTierCheckTask source, AccountTierCheckTask task,
            AccountTierCheckRequestDTO req, long now) {
        var current = mapper.candidates(req, AccountTierCheckPolicy.MAX_ACCOUNTS).stream()
                .collect(Collectors.toMap(AccountTierCheckCandidate::id, a -> a));
        var q = new AccountTierCheckQuery(); q.setPageSize(INSERT_BATCH);
        for (int page = 1; ; page++) {
            q.setPage(page);
            var old = mapper.items(source.getId(), q);
            if (old.isEmpty()) break;
            var batch = new ArrayList<AccountTierCheckItem>();
            for (var previous : old) {
                var a = current.get(previous.getAccountId());
                var item = new AccountTierCheckItem();
                item.setTenantId(task.getTenantId()); item.setTaskId(task.getId()); item.setAccountId(previous.getAccountId());
                item.setPhone(previous.getPhone()); item.setTargetIdentity(previous.getTargetIdentity());
                // 同号码换过凭据是新的合法快照；号码已换或账号删除时保留旧目标，由调度显式记失败。
                if (a != null && Objects.equals(a.wsPhone(), previous.getPhone())) item.setTargetIdentity(AccountTierCheckPolicy.identity(a));
                item.setCreatedAt(now); item.setUpdatedAt(now); batch.add(item);
            }
            mapper.insertItems(batch);
        }
    }
    private static AccountTierCheckItem newItem(AccountTierCheckTask task, AccountTierCheckCandidate a, long now) {
        var item = new AccountTierCheckItem();
        item.setTenantId(task.getTenantId()); item.setTaskId(task.getId()); item.setAccountId(a.id());
        item.setPhone(a.wsPhone()); item.setTargetIdentity(AccountTierCheckPolicy.identity(a));
        item.setCreatedAt(now); item.setUpdatedAt(now);
        return item;
    }
    private static AccountTierCheckTask newTask(AccountTierCheckRequestDTO req, AuthPrincipal p, String fingerprint, long now) {
        var task = new AccountTierCheckTask();
        task.setTenantId(p.tenantId()); task.setCreatedBy(p.userId()); task.setRequestId(req.requestId());
        task.setRequestFingerprint(fingerprint); task.setName(req.name()); task.setMode(req.mode());
        task.setProbeAccountIds(req.probeAccountIds().toString()); task.setSourceTaskId(req.sourceTaskId());
        task.setStatus(AccountTierCheckState.RUNNING); task.setCreatedAt(now); task.setUpdatedAt(now);
        return task;
    }
    private void insert(AccountTierCheckTask task) {
        try { mapper.insertTask(task); }
        catch (DuplicateKeyException ex) { throw new BusinessException(ErrorCode.CONFLICT, "重复创建请求，请使用原请求重试"); }
    }
    private static AccountTierCheckTask requireTask(AccountTierCheckTask task, AuthPrincipal p) {
        AccountTierCheckPolicy.require(p);
        if (task == null || (!AccountTierCheckPolicy.admin(p) && !Objects.equals(task.getCreatedBy(), p.userId())))
            throw new BusinessException(ErrorCode.NOT_FOUND, "批次不存在或无权访问");
        return task;
    }
    private AccountTierCheckTaskVO toVO(AccountTierCheckTask task) {
        var source = task.getSourceTaskId() == null ? null : mapper.task(task.getSourceTaskId());
        Map<String, Long> tiers = mapper.tierCounts(task.getId()).stream().collect(Collectors.toMap(
                com.armada.account.model.vo.AccountTierCheckTierCountVO::trustTier,
                com.armada.account.model.vo.AccountTierCheckTierCountVO::count));
        return converter.task(task, source == null ? null : source.getName(), tiers, mapper.stats(task.getId()));
    }
    private record Plan(List<AccountTierCheckCandidate> targets, AccountTierCheckPreviewVO preview) {}
}
