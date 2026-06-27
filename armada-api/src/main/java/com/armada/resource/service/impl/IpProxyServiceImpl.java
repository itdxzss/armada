package com.armada.resource.service.impl;

import com.armada.resource.converter.IpProxyConverter;
import com.armada.resource.mapper.IpProxyBindTarget;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.dto.IpProxyImportDTO;
import com.armada.resource.model.dto.IpProxyQuery;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.vo.IpProxyImportResultVO;
import com.armada.resource.model.vo.IpProxyVO;
import com.armada.resource.service.IpProxyAccountAllocation;
import com.armada.resource.service.IpProxyAllocation;
import com.armada.resource.service.IpProxyService;
import com.armada.platform.proxy.ProxyCredentials;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.util.ImportLineException;
import com.armada.shared.util.LineImporter;
import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IP 代理池业务实现。
 *
 * <p>普通 CRUD 的租户隔离由 MyBatis 租户拦截器透明完成；上线分配的 {@code FOR UPDATE} 查询显式传 tenantId,
 * 避免租户 SQL 改写影响锁行语义。导入复用 {@link LineImporter} 逐行骨架，仅提供「解析校验 / 去重键 / 落库」
 * 三段差异；新行的 status/ownership 用枚举码（禁魔法值）。</p>
 */
@Service
public class IpProxyServiceImpl implements IpProxyService {

    private static final Logger log = LoggerFactory.getLogger(IpProxyServiceImpl.class);

    /** 一行原文字段数：host:port:username:password。 */
    private static final int IMPORT_FIELDS = 4;
    private static final int MAX_BATCH_ALLOCATION_SIZE = 500;

    private final IpProxyMapper mapper;
    private final IpProxyConverter converter;

    public IpProxyServiceImpl(IpProxyMapper mapper, IpProxyConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    @Override
    public PageResult<IpProxyVO> list(IpProxyQuery query) {
        long total = mapper.countPage(query);
        List<IpProxyVO> rows = total == 0
                ? List.of()
                : converter.toVOList(mapper.selectPage(query));
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IpProxyImportResultVO importProxies(IpProxyImportDTO dto) {
        // 先校验批次级字段。国家可为空,但协议、来源、导入文本必须完整,否则不进入逐行解析。
        validateImport(dto);

        // LineImporter 负责统一处理逐行导入流程:
        // 1) 跳过空行;2) parseProxyLine 校验单行格式;3) dedupKey 做批内去重;
        // 4) persistProxy 返回 true=新增,false=库内已有而跳过。
        List<LineOutcome<ProxyLine, Boolean>> outcomes = LineImporter.run(
                dto.text(), IpProxyServiceImpl::parseProxyLine, ProxyLine::dedupKey,
                line -> persistProxy(dto, line));   // 返回 true=新增 false=库内已存在跳过

        // 汇总口径:
        // failed=格式/字段校验失败;inserted=真实新增;skipped=批内重复 + 库内已存在。
        int total = outcomes.size();
        int failed = (int) outcomes.stream().filter(o -> o.kind() == Kind.FAILED).count();
        int inserted = (int) outcomes.stream()
                .filter(o -> o.kind() == Kind.PERSISTED && Boolean.TRUE.equals(o.persistResult())).count();
        int skipped = total - failed - inserted;   // 批内重复 + 库内已存在

        // 错误信息带原始行号,让页面能提示用户直接回到具体问题行。
        List<String> errors = outcomes.stream().filter(o -> o.kind() == Kind.FAILED)
                .map(o -> "第 " + o.lineNo() + " 行：" + o.reason()).toList();
        log.info("IP代理导入 region={} protocol={} total={} inserted={} skipped={} failed={}",
                dto.region(), dto.protocol(), total, inserted, skipped, failed);
        return new IpProxyImportResultVO(total, inserted, skipped, failed, errors);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IpProxyAllocation allocateOnlineEndpoint(Long accountId) {
        // 分配代理必须绑定到具体账号;没有账号 ID 时不访问 DB。
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
        }

        // selectOneIdleForUpdate 会显式写 tenant_id 并关闭租户拦截器,所以这里必须先拿到租户上下文。
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "缺少租户上下文");
        }

        long now = System.currentTimeMillis();

        // 用户点击上线不保留原 IP:先释放该账号旧绑定,再重新从空闲池选一条。
        // 这一步和后面的锁行/绑定处在同一个短事务内,只保护本地代理占用关系。
        int released = mapper.releaseByAccount(
                accountId,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now);

        // 锁定一条本租户空闲代理,防止两个账号并发拿到同一行。
        // 这里不等待协议层上线结果;HTTP /online 后续是否成功由 Kafka 异步回填。
        IpProxy proxy = mapper.selectOneIdleForUpdate(tenantId, IpProxyStatus.IDLE.code());
        if (proxy == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "暂无空闲代理");
        }

        // 标记使用中时再带 status=IDLE 条件兜底;如果更新不到 1 行,说明并发状态已变化,让调用方重试。
        int marked = mapper.markUsingAndBind(
                proxy.getId(),
                accountId,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now);
        if (marked != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "代理分配冲突,请重试");
        }

        proxy.setStatus(IpProxyStatus.IN_USE.code());
        proxy.setBoundAccountId(accountId);
        proxy.setBoundAt(now);

        // 只记录定位需要的代理 ID 和地区,不打印 username/password。
        log.info("IP代理上线分配 accountId={} released={} proxyId={} region={}",
                accountId, released, proxy.getId(), proxy.getRegion());
        return new IpProxyAllocation(proxy.getId(), toEndpoint(proxy));
    }

    /**
     * 批量为账号上线分配代理端点。
     *
     * <p>这是批量上线的本地 DB 临界区,只负责代理池占用关系,不调用协议层、也不等待 Kafka 回填。
     * 方法会先释放这些账号原有的 IN_USE 绑定,再一次性锁定同等数量的 IDLE 代理行,最后批量置为
     * IN_USE 并绑定到对应账号。</p>
     *
     * <p>整个方法处在一个短事务内:如果空闲代理不足、批量绑定行数不匹配或发生其它异常,
     * 前面的释放旧绑定也会一起回滚,避免账号丢失原代理绑定。返回结果按入参账号顺序排列,
     * account 域后续用其中的 endpoint 调协议层 batch online,用 proxyId/accountId 做失败补偿释放。</p>
     *
     * @param accountIds 需要上线的账号 ID,不能为空、不能重复,最多 500 个
     * @return 每个账号本次分配到的代理 ID 和协议层可用代理端点
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<IpProxyAccountAllocation> allocateOnlineEndpoints(List<Long> accountIds) {
        List<Long> ids = normalizeAccountIds(accountIds);
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "缺少租户上下文");
        }

        long now = System.currentTimeMillis();

        // 批量上线不保留原 IP:先释放这些账号旧绑定,再重新从空闲池按账号顺序分配新 IP。
        // 整个方法在一个短事务内;如果后面空闲代理不足或绑定冲突,这里会一起回滚。
        int released = mapper.releaseByAccounts(
                ids,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now);

        // 一次锁定本批所需数量的空闲代理。FOR UPDATE 只保护本地代理池占用关系,不跨协议 HTTP。
        List<IpProxy> proxies = mapper.selectIdleForUpdate(tenantId, IpProxyStatus.IDLE.code(), ids.size());
        if (proxies.size() < ids.size()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "暂无足够空闲代理: requested=" + ids.size() + " available=" + proxies.size());
        }

        List<IpProxyBindTarget> targets = new ArrayList<>(ids.size());
        List<IpProxyAccountAllocation> allocations = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            Long accountId = ids.get(i);
            IpProxy proxy = proxies.get(i);
            targets.add(new IpProxyBindTarget(proxy.getId(), accountId));
            allocations.add(new IpProxyAccountAllocation(accountId, proxy.getId(), toEndpoint(proxy)));
        }

        int marked = mapper.markUsingAndBindBatch(
                targets,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                now);
        if (marked != ids.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "代理批量分配冲突,请重试");
        }

        log.info("IP代理批量上线分配 requested={} released={} allocated={}",
                ids.size(), released, allocations.size());
        return allocations;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseOnlineAllocation(Long accountId, Long proxyId) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
        }
        if (proxyId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "代理 ID 不能为空");
        }

        int released = mapper.releaseOnlineAllocation(
                accountId,
                proxyId,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                System.currentTimeMillis());
        log.info("IP代理上线补偿释放 accountId={} proxyId={} released={}", accountId, proxyId, released);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseOnlineAllocations(List<IpProxyAccountAllocation> allocations) {
        List<IpProxyBindTarget> targets = toReleaseTargets(allocations);
        int released = mapper.releaseOnlineAllocations(
                targets,
                IpProxyStatus.IDLE.code(),
                IpProxyStatus.IN_USE.code(),
                System.currentTimeMillis());
        log.info("IP代理上线批量补偿释放 requested={} released={}", targets.size(), released);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        int count = mapper.softDeleteByIds(ids, System.currentTimeMillis());
        log.info("IP代理批量软删除 count={} ids={}", count, ids);
    }

    /**
     * 校验一次导入任务的批次级属性。
     *
     * <p>这里不处理单行代理格式,只检查所有行共享的协议、来源和导入文本。批次级字段不合法时,
     * 直接中断导入,避免后续逐行解析产生一堆没有意义的行级错误。</p>
     */
    private void validateImport(IpProxyImportDTO dto) {
        if (dto.protocol() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "代理类型不能为空");
        }
        ProxyProtocol.fromCode(dto.protocol());
        if (!StringUtils.hasText(dto.source())) {
            throw new BusinessException(ErrorCode.VALIDATION, "来源不能为空");
        }
        if (!StringUtils.hasText(dto.text())) {
            throw new BusinessException(ErrorCode.VALIDATION, "导入内容不能为空");
        }
    }

    /**
     * 归一化批量分配的账号 ID 列表。
     *
     * <p>批量代理分配后会按账号顺序返回 allocation,因此这里既要拒绝 null/重复账号,
     * 也要保持入参顺序。使用 {@link LinkedHashSet} 是为了在校验重复的同时保留顺序,
     * 最后返回不可变 List,避免后续事务内参数被外部改动。</p>
     *
     * @param accountIds 待上线账号 ID
     * @return 已校验、保序、不可变的账号 ID 列表
     */
    private static List<Long> normalizeAccountIds(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
        }
        if (accountIds.size() > MAX_BATCH_ALLOCATION_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "批量代理分配一次最多 " + MAX_BATCH_ALLOCATION_SIZE + " 个账号");
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Long accountId : accountIds) {
            if (accountId == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
            }
            if (!seen.add(accountId)) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能重复: " + accountId);
            }
        }
        return List.copyOf(seen);
    }

    /**
     * 将分配结果转换为 mapper 精确释放参数。
     *
     * <p>补偿释放必须同时带 proxyId 和 accountId,不能只按账号释放。否则旧上线请求失败时,
     * 可能把同一账号后续重新分配的新代理错误释放回空闲池。这里提前校验两个 ID 非空,
     * 保证 SQL 只会释放明确的「本次分配」绑定。</p>
     */
    private static List<IpProxyBindTarget> toReleaseTargets(List<IpProxyAccountAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "代理释放列表不能为空");
        }
        List<IpProxyBindTarget> targets = new ArrayList<>(allocations.size());
        for (IpProxyAccountAllocation allocation : allocations) {
            if (allocation == null || allocation.accountId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
            }
            if (allocation.proxyId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "代理 ID 不能为空");
            }
            targets.add(new IpProxyBindTarget(allocation.proxyId(), allocation.accountId()));
        }
        return targets;
    }

    /** 一行代理：host:port:用户名:密码。 */
    private record ProxyLine(String host, int port, String username, String password) {
        /** 批内去重键：完整身份。分隔避免字段拼接歧义。 */
        Object dedupKey() {
            return host + ' ' + port + ' ' + username + ' ' + password;
        }
    }

    /**
     * 解析并校验一行代理文本。
     *
     * <p>当前导入格式固定为 {@code host:port:username:password}。这里故意只接受四段,
     * 不做智能容错,因为代理账号密码本身经常包含业务后缀,错行应该尽早暴露给用户。
     * 不合格时抛 {@link ImportLineException},由 {@link LineImporter} 记录成 {@code Kind.FAILED}。</p>
     */
    private static ProxyLine parseProxyLine(String line) {
        String[] parts = line.split(":", -1);
        if (parts.length != IMPORT_FIELDS) {
            throw new ImportLineException("格式错误，应为 host:port:用户名:密码");
        }
        String host = parts[0].trim();
        String portText = parts[1].trim();
        String username = parts[2].trim();
        String password = parts[3].trim();
        if (!isPositiveInt(portText)) {
            throw new ImportLineException("端口非法");
        }
        if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
            throw new ImportLineException("存在空字段");
        }
        return new ProxyLine(host, Integer.parseInt(portText), username, password);
    }

    /**
     * 落库:DB 去重命中→false(跳过),否则插入→true。
     *
     * 协议/国家/来源取本次统一属性，新行状态=空闲、归属=租户自有。
     * 真正唯一性仍由数据库唯一键兜底,这里的 count 只是为了给导入结果返回友好的 skipped 统计。
     */
    private boolean persistProxy(IpProxyImportDTO dto, ProxyLine line) {
        if (mapper.countActiveByFullTuple(line.host(), line.port(), line.username(), line.password()) > 0) {
            return false;
        }
        IpProxy row = new IpProxy();
        row.setHost(line.host());
        row.setPort(line.port());
        row.setUsername(line.username());
        row.setPassword(line.password());
        row.setProtocol(dto.protocol());
        row.setRegion(dto.region());
        row.setSource(dto.source());
        row.setStatus(IpProxyStatus.IDLE.code());
        row.setOwnership(ProxyOwnership.OWNED.code());
        long now = System.currentTimeMillis();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insert(row);
        return true;
    }

    /**
     * 将 ip_proxy 表行转换为协议层上线需要的代理端点模型。
     *
     * <p>这里会携带代理用户名和密码,因此调用方可以把 endpoint 传给协议层,
     * 但日志只能打印 proxyId/region 等定位字段,不能打印完整 endpoint。</p>
     */
    private static ProxyEndpoint toEndpoint(IpProxy proxy) {
        return new ProxyEndpoint(
                proxy.getProtocol(),
                proxy.getHost(),
                proxy.getPort(),
                new ProxyCredentials(proxy.getUsername(), proxy.getPassword()),
                proxy.getRegion());
    }

    /**
     * 判断文本是否为正整数字符串。
     *
     * <p>端口解析前先逐字符校验,避免 {@link Integer#parseInt(String)} 的异常泄露到导入流程里。
     * 这里不接受正负号、小数和空字符串。</p>
     */
    private static boolean isPositiveInt(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
