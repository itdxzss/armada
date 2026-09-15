package com.armada.resource.service.impl;

import com.armada.resource.converter.GroupDataPackageConverter;
import com.armada.resource.mapper.GroupDataPackageMapper;
import com.armada.resource.mapper.GroupDataPackagePhoneMapper;
import com.armada.resource.mapper.GroupDataPackageStatMapper;
import com.armada.resource.model.dto.GroupDataPackageCreateDTO;
import com.armada.resource.model.dto.GroupDataPackageUpdateDTO;
import com.armada.resource.model.dto.GroupDataPackageQuery;
import com.armada.resource.model.dto.GroupDataPackagePhoneQuery;
import com.armada.resource.model.entity.GroupDataPackage;
import com.armada.resource.model.entity.GroupDataPackageStat;
import com.armada.resource.model.enums.GroupDataPackagePhoneStatus;
import com.armada.resource.model.vo.GroupDataPackageVO;
import com.armada.resource.model.vo.GroupDataPackagePhoneVO;
import com.armada.resource.service.GroupDataPackageService;
import com.armada.task.service.GroupDataPackageTaskProjectionService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** 包管理；不重新实现任务执行器。 */
@Service
public class GroupDataPackageServiceImpl implements GroupDataPackageService {
    private static final int NAME_LIMIT = 128;
    private static final int REMARK_LIMIT = 512;
    private final GroupDataPackageMapper packages;
    private final GroupDataPackagePhoneMapper phones;
    private final GroupDataPackageStatMapper stats;
    private final GroupDataPackageConverter converter;
    private final GroupDataPackageTaskProjectionService projection;

    /** 注入管理及任务事实服务。 */
    public GroupDataPackageServiceImpl(GroupDataPackageMapper packages, GroupDataPackagePhoneMapper phones,
            GroupDataPackageStatMapper stats, GroupDataPackageConverter converter,
            GroupDataPackageTaskProjectionService projection) {
        this.packages = packages; this.phones = phones; this.stats = stats;
        this.converter = converter; this.projection = projection;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<GroupDataPackageVO> list(GroupDataPackageQuery query) {
        normalize(query);
        long count = packages.count(query);
        List<GroupDataPackage> rows = packages.page(query);
        if (rows.isEmpty()) { return PageResult.of(List.of(), query.getPage(), query.getPageSize(), count); }
        List<Long> ids = rows.stream().map(GroupDataPackage::getId).toList();
        Map<Long, GroupDataPackageStat> byId = stats.selectByIds(ids).stream()
                .collect(Collectors.toMap(GroupDataPackageStat::getPackageId, Function.identity()));
        return PageResult.of(rows.stream().map(row -> converter.detail(row, byId.get(row.getId()))).toList(),
                query.getPage(), query.getPageSize(), count);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupDataPackageVO detail(long id) {
        GroupDataPackage row = require(id, false);
        return converter.detail(row, stats.select(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupDataPackageVO create(GroupDataPackageCreateDTO request, long userId) {
        if (request == null || userId < 1) { throw invalid("创建参数不完整"); }
        GroupDataPackage row = new GroupDataPackage();
        row.setName(text(request.name(), NAME_LIMIT, true));
        row.setRemark(text(request.remark(), REMARK_LIMIT, false));
        row.setCreatedBy(userId); row.setCreatedAt(System.currentTimeMillis());
        row.setUpdatedAt(row.getCreatedAt());
        packages.insert(row); stats.insert(row.getId());
        return converter.detail(require(row.getId(), false), stats.select(row.getId()));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupDataPackageVO update(long id, GroupDataPackageUpdateDTO request) {
        if (request == null || request.version() == null) { throw invalid("缺少数据版本"); }
        GroupDataPackage row = require(id, true);
        row.setName(text(request.name(), NAME_LIMIT, true)); row.setRemark(text(request.remark(), REMARK_LIMIT, false));
        row.setVersion(request.version()); row.setUpdatedAt(System.currentTimeMillis());
        if (packages.updateMetadata(row) != 1) { throw conflict("数据已被编辑，请刷新后重试"); }
        return converter.detail(require(id, false), stats.select(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<GroupDataPackagePhoneVO> phones(long id, GroupDataPackagePhoneQuery query) {
        GroupDataPackage row = require(id, false);
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            query.setStatusCode(GroupDataPackagePhoneStatus.parse(query.getStatus()).code());
        } else { query.setStatusCode(null); }
        return PageResult.of(phones.page(id, row.getGeneration(), query).stream().map(converter::phone).toList(),
                query.getPage(), query.getPageSize(), phones.count(id, row.getGeneration(), query));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int resetFailed(long id) {
        require(id, false);
        projection.synchronize(List.of(id));
        GroupDataPackage row = require(id, true);
        projection.assertNotActivelyUsed(id);
        int affected = phones.resetFailed(id, row.getGeneration(), System.currentTimeMillis());
        if (affected > 0 && stats.move(id, row.getGeneration(), GroupDataPackagePhoneStatus.RETRYABLE_FAILED.code(),
                GroupDataPackagePhoneStatus.UNUSED.code(), affected) != 1) { throw conflict("统计状态已变化"); }
        return affected;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void delete(long id) {
        require(id, false);
        projection.synchronize(List.of(id));
        require(id, true);
        projection.assertNotActivelyUsed(id);
        if (phones.activeCount(id) > 0) { throw conflict("仍有占用或待确认号码，不能删除"); }
        packages.delete(id, System.currentTimeMillis());
    }

    private GroupDataPackage require(long id, boolean lock) {
        GroupDataPackage value = lock ? packages.lockActive(id) : packages.selectActive(id);
        if (value == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "数据包不存在或已删除"); }
        return value;
    }

    private static void normalize(GroupDataPackageQuery query) {
        if (query.getCountryIso2() != null) { query.setCountryIso2(query.getCountryIso2().trim().toUpperCase(Locale.ROOT)); }
        if (query.getContinent() != null) { query.setContinent(query.getContinent().trim().toUpperCase(Locale.ROOT)); }
        if (query.getUsageBusiness() != null && !query.getUsageBusiness().isBlank()
                && !"STANDARD_PULL".equals(query.getUsageBusiness())) { throw invalid("消费业务不支持"); }
        if (query.getCreatedFrom() != null && query.getCreatedTo() != null
                && query.getCreatedFrom() > query.getCreatedTo()) { throw invalid("创建时间范围不合法"); }
        if (query.getName() != null) { query.setName(query.getName().trim()); }
    }

    private static String text(String value, int limit, boolean required) {
        String normalized = value == null ? "" : value.trim();
        if ((required && normalized.isEmpty()) || normalized.length() > limit) { throw invalid("名称或备注长度不合法"); }
        return normalized;
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION, message); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }
}
