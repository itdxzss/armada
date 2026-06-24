package com.armada.resource.service.impl;

import com.armada.resource.converter.IpProxyConverter;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.dto.IpProxyImportDTO;
import com.armada.resource.model.dto.IpProxyQuery;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.vo.IpProxyImportResultVO;
import com.armada.resource.model.vo.IpProxyVO;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.util.ImportLineException;
import com.armada.shared.util.LineImporter;
import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IP 代理池业务实现。
 *
 * <p>租户隔离由 MyBatis 租户拦截器透明完成，本类不手写 tenant_id。导入复用 {@link LineImporter} 逐行骨架，
 * 仅提供「解析校验 / 去重键 / 落库」三段差异；新行的 status/ownership 用枚举码（禁魔法值）。</p>
 */
@Service
public class IpProxyServiceImpl implements IpProxyService {

    private static final Logger log = LoggerFactory.getLogger(IpProxyServiceImpl.class);

    /** 一行原文字段数：host:port:username:password。 */
    private static final int IMPORT_FIELDS = 4;

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
        validateImport(dto);
        List<LineOutcome<ProxyLine, Boolean>> outcomes = LineImporter.run(
                dto.text(), IpProxyServiceImpl::parseProxyLine, ProxyLine::dedupKey,
                line -> persistProxy(dto, line));   // 返回 true=新增 false=库内已存在跳过
        int total = outcomes.size();
        int failed = (int) outcomes.stream().filter(o -> o.kind() == Kind.FAILED).count();
        int inserted = (int) outcomes.stream()
                .filter(o -> o.kind() == Kind.PERSISTED && Boolean.TRUE.equals(o.persistResult())).count();
        int skipped = total - failed - inserted;   // 批内重复 + 库内已存在
        List<String> errors = outcomes.stream().filter(o -> o.kind() == Kind.FAILED)
                .map(o -> "第 " + o.lineNo() + " 行：" + o.reason()).toList();
        log.info("IP代理导入 region={} protocol={} total={} inserted={} skipped={} failed={}",
                dto.region(), dto.protocol(), total, inserted, skipped, failed);
        return new IpProxyImportResultVO(total, inserted, skipped, failed, errors);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        int count = mapper.softDeleteByIds(ids);
        log.info("IP代理批量软删除 count={} ids={}", count, ids);
    }

    /** 导入统一属性校验：协议码合法、来源/内容非空。 */
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

    /** 一行代理：host:port:用户名:密码。 */
    private record ProxyLine(String host, int port, String username, String password) {
        /** 批内去重键：完整身份。分隔避免字段拼接歧义。 */
        Object dedupKey() {
            return host + ' ' + port + ' ' + username + ' ' + password;
        }
    }

    /** 解析+校验一行；不合格抛 {@link ImportLineException}（被 LineImporter 产出 {@code Kind.FAILED} outcome）。 */
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
     * 协议/国家/来源取本次统一属性，新行状态=空闲、归属=租户自有。
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
        mapper.insert(row);
        return true;
    }

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
