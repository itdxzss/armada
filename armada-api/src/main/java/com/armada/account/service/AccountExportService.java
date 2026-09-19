package com.armada.account.service;

import com.armada.account.mapper.AccountExportMapper;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.model.dto.AccountExportCandidate;
import com.armada.account.model.dto.AccountExportCreateDTO;
import com.armada.account.model.entity.AccountExportJob;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountExportJobVO;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原格式导出交接；只校验离线，不替业务人员执行下线。 */
@Service
public class AccountExportService {
    private static final String READY = "READY";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELLED = "CANCELLED";
    private static final int MAX_ACCOUNTS = 500;
    private static final long FILE_TTL = 24 * 60 * 60 * 1000L;
    private final AccountExportMapper mapper;
    private final AccountGroupMapper groups;
    private final AccountExportArchive archive;
    private final ProtocolCommandOutboxService outbox;
    private final IpProxyService proxies;
    private final ObjectMapper json = new ObjectMapper();

    /** 注入账号域查询、原格式封装及已有协议命令/代理释放能力。 */
    public AccountExportService(AccountExportMapper mapper, AccountGroupMapper groups,
            AccountExportArchive archive, ProtocolCommandOutboxService outbox, IpProxyService proxies) {
        this.mapper = mapper;
        this.groups = groups;
        this.archive = archive;
        this.outbox = outbox;
        this.proxies = proxies;
    }

    /** 校验全部勾选账号并持久化 ZIP；任意失败整个事务回滚，不删除账号。 */
    @Transactional(rollbackFor = Exception.class)
    public AccountExportJobVO create(AccountExportCreateDTO request, AuthPrincipal principal) {
        requirePrincipal(principal);
        if (request == null) throw invalid("请选择需要导出的账号");
        String id = requestId(request.requestId());
        List<Long> ids = normalizeIds(request.ids());
        AccountExportJob existing = mapper.findJob(id);
        if (existing != null) {
            requireOwner(existing, principal);
            if (!readIds(existing).equals(ids)) throw invalid("相同导出请求不能更换勾选账号");
            return toVO(existing);
        }
        List<AccountExportCandidate> rows = mapper.candidates(ids);
        List<Long> groupIds = rows.stream().map(AccountExportCandidate::groupId).filter(Objects::nonNull).distinct().toList();
        validateSelection(ids, rows, principal, false);
        if (!groupIds.isEmpty() && groups.countActiveBuilderGroupReferences(groupIds) > 0) {
            throw invalid("所选账号分组正在执行建群任务，请先结束任务");
        }
        var materials = mapper.materials(ids);
        if (materials.size() != ids.size()
                || !materials.stream().map(row -> row.accountId()).sorted().toList().equals(ids)) {
            throw invalid("所选账号缺少唯一的原始导入材料，无法按原格式导出");
        }
        byte[] file = archive.build(materials);
        long now = System.currentTimeMillis();
        AccountExportJob job = newJob(id, ids, principal, file, now);
        mapper.insertJob(job);
        mapper.insertItems(id, rows);
        if (mapper.reserve(rows, now) != ids.size()) throw invalid("账号状态已变化，请刷新后重试");
        // 只取消尚未发布的历史上线请求，绝不发送下线或解绑命令。
        outbox.cancelPendingAccountOnlineCommands(ids);
        return toVO(job);
    }

    /** 最近 50 次本人导出；到期未交付作业取消，仍保留历史记录。 */
    @Transactional(rollbackFor = Exception.class)
    public List<AccountExportJobVO> list(AuthPrincipal principal) {
        requirePrincipal(principal);
        long now = System.currentTimeMillis();
        for (String id : mapper.expiredReady(principal.userId(), now)) cancelLocked(owned(id, principal));
        mapper.purgeArchives(principal.userId(), now);
        return mapper.listJobs(principal.userId()).stream().map(this::toVO).toList();
    }

    /** 下载前复核离线和预占；已完成交付允许在有效期内重下载。 */
    @Transactional(rollbackFor = Exception.class)
    public AccountExportJob download(String id, AuthPrincipal principal) {
        var job = owned(id, principal);
        requireDownloadable(job);
        if (READY.equals(job.getStatus())) {
            List<Long> ids = readIds(job);
            validateSelection(ids, mapper.candidates(ids), principal, true);
        }
        return job;
    }

    /** 客户端收到完整 ZIP 后才移除；摘要、状态和账号范围均服务端复核，重复回执幂等。 */
    @Transactional(rollbackFor = Exception.class)
    public AccountExportJobVO complete(String id, String digest, AuthPrincipal principal) {
        var job = owned(id, principal);
        if (!Objects.equals(job.getSha256(), digest)) throw invalid("下载文件摘要不匹配，请重新下载");
        if (COMPLETED.equals(job.getStatus())) return toVO(job);
        requireDownloadable(job);
        List<Long> ids = readIds(job);
        List<AccountExportCandidate> rows = mapper.candidates(ids);
        List<Long> groupIds = rows.stream().map(AccountExportCandidate::groupId).filter(Objects::nonNull).distinct().toList();
        validateSelection(ids, rows, principal, true);
        if (!groupIds.isEmpty() && groups.countActiveBuilderGroupReferences(groupIds) > 0) {
            throw invalid("账号分组已被任务占用，暂不能移除，请结束任务后重试");
        }
        long now = System.currentTimeMillis();
        if (mapper.removeAccounts(rows, now) != ids.size()) throw invalid("账号状态已变化，未执行移除");
        mapper.removeCredentials(ids, now);
        outbox.cancelPendingAccountOnlineCommands(ids);
        for (Long accountId : ids) proxies.releaseByAccount(accountId);
        if (mapper.complete(id, now) != 1) throw invalid("导出状态已变化，请刷新后重试");
        job.setStatus(COMPLETED);
        job.setCompletedAt(now);
        return toVO(job);
    }

    /** 未交付时允许取消并恢复原业务状态；不自动上线，不撤销已完成的移除。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String id, AuthPrincipal principal) {
        cancelLocked(owned(id, principal));
    }

    /** 内部到期维护；调用方已恢复租户上下文，不接受浏览器发起此操作。 */
    @Transactional(rollbackFor = Exception.class)
    public void expire(String id) {
        if (TenantContext.get() == null) throw new BusinessException(ErrorCode.TENANT_MISSING);
        var job = mapper.lockJob(id);
        long now = System.currentTimeMillis();
        if (job == null || job.getExpiresAt() > now) return;
        if (READY.equals(job.getStatus())) cancelLocked(job);
        mapper.purgeArchive(id, now);
    }

    private void cancelLocked(AccountExportJob job) {
        if (CANCELLED.equals(job.getStatus())) return;
        if (!READY.equals(job.getStatus())) throw invalid("已完成导出不能取消");
        mapper.restore(job.getId(), System.currentTimeMillis());
        mapper.cancel(job.getId());
    }

    private void validateSelection(List<Long> ids, List<AccountExportCandidate> rows,
            AuthPrincipal principal, boolean reserved) {
        if (rows.size() != ids.size()) throw invalid("部分所选账号不存在、已删除或无权访问");
        for (var row : rows) {
            if (!principal.roleCodes().contains("TENANT_ADMIN") && row.ownerUserId() != null
                    && !Objects.equals(row.ownerUserId(), principal.userId())) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED);
            }
            if (!Integer.valueOf(AccountLoginStateCode.OFFLINE).equals(row.loginState())) {
                throw invalid("所选账号包含非离线账号，请先批量离线并等待完成后再导出");
            }
            if (row.dispatchedAt() != null || row.marketingTaskId() != null) {
                throw invalid("所选账号被任务占用，请先结束任务再导出");
            }
            boolean exported = Integer.valueOf(AccountStateCode.EXPORTED).equals(row.accountState());
            if (exported != reserved) throw invalid("账号已进入其他导出或状态已变化，请检查导出记录");
        }
    }

    private AccountExportJob owned(String id, AuthPrincipal principal) {
        requirePrincipal(principal);
        AccountExportJob job = mapper.lockJob(requestId(id));
        if (job == null) throw new BusinessException(ErrorCode.NOT_FOUND, "导出记录不存在");
        requireOwner(job, principal);
        return job;
    }

    private void requireOwner(AccountExportJob job, AuthPrincipal principal) {
        if (!Objects.equals(job.getCreatedBy(), principal.userId())
                || !Objects.equals(job.getTenantId(), principal.tenantId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private void requirePrincipal(AuthPrincipal principal) {
        if (principal == null || !Objects.equals(TenantContext.get(), principal.tenantId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private void requireDownloadable(AccountExportJob job) {
        if (CANCELLED.equals(job.getStatus())) throw invalid("本次导出已取消");
        if (job.getExpiresAt() <= System.currentTimeMillis() || job.getArchive() == null) {
            throw invalid("导出文件已过期，请刷新导出记录后重新选择账号");
        }
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_ACCOUNTS
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw invalid("请勾选 1 至 500 个有效账号");
        }
        return ids.stream().distinct().sorted().toList();
    }

    private String requestId(String id) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw invalid("导出请求编号无效");
            return id;
        } catch (IllegalArgumentException ex) {
            throw invalid("导出请求编号无效");
        }
    }

    private List<Long> readIds(AccountExportJob job) {
        try {
            return json.readValue(job.getAccountIdsJson(), new TypeReference<List<Long>>() { });
        } catch (IOException ex) {
            throw invalid("导出账号快照损坏");
        }
    }

    private AccountExportJob newJob(String id, List<Long> ids, AuthPrincipal principal, byte[] file, long now) {
        AccountExportJob job = new AccountExportJob();
        job.setId(id);
        job.setTenantId(principal.tenantId());
        job.setCreatedBy(principal.userId());
        job.setStatus(READY);
        job.setAccountIdsJson(ids.toString());
        job.setAccountCount(ids.size());
        job.setFilename("accounts-" + id + ".zip");
        job.setArchive(file);
        job.setFileSize(file.length);
        try {
            job.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        job.setCreatedAt(now);
        job.setExpiresAt(now + FILE_TTL);
        return job;
    }

    private AccountExportJobVO toVO(AccountExportJob job) {
        return new AccountExportJobVO(job.getId(), job.getStatus(), job.getAccountCount(),
                job.getFilename(), job.getSha256(), job.getFileSize(), job.getCreatedAt(), job.getExpiresAt());
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
