package com.armada.hyperlink.data.service.impl;

import com.armada.hyperlink.data.converter.DataPackageConverter;
import com.armada.hyperlink.data.mapper.DataPackageMapper;
import com.armada.hyperlink.data.mapper.DataPackagePhoneMapper;
import com.armada.hyperlink.data.mapper.DataPackageStatMapper;
import com.armada.hyperlink.data.model.dto.DataPackageCreateDTO;
import com.armada.hyperlink.data.model.dto.DataPackagePhoneQuery;
import com.armada.hyperlink.data.model.dto.DataPackageQuery;
import com.armada.hyperlink.data.model.dto.DataPackageUpdateDTO;
import com.armada.hyperlink.data.model.entity.DataPackage;
import com.armada.hyperlink.data.model.entity.DataPackageStat;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.armada.hyperlink.data.model.vo.DataPackageCountryOptionVO;
import com.armada.hyperlink.data.model.vo.DataPackageCountryRow;
import com.armada.hyperlink.data.model.vo.DataPackageDetailVO;
import com.armada.hyperlink.data.model.vo.DataPackageListItemVO;
import com.armada.hyperlink.data.model.vo.DataPackageListRow;
import com.armada.hyperlink.data.model.vo.DataPackagePhoneItemVO;
import com.armada.hyperlink.data.service.DataPackageService;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 数据包主数据和当前代只读查询实现。 */
@Service
public class DataPackageServiceImpl implements DataPackageService {

    private static final int NAME_MAX_LENGTH = 128;
    private static final int REMARK_MAX_LENGTH = 255;
    private static final int PHONE_MAX_LENGTH = 20;
    private static final String UNKNOWN_COUNTRY = "UNKNOWN";
    private static final Pattern ISO2_PATTERN = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern PHONE_FILTER_PATTERN = Pattern.compile("^[0-9]+$");

    private final DataPackageMapper mapper;
    private final DataPackagePhoneMapper phoneMapper;
    private final DataPackageStatMapper statMapper;
    private final CountryService countryService;
    private final DataPackageConverter converter;

    public DataPackageServiceImpl(
            DataPackageMapper mapper,
            DataPackagePhoneMapper phoneMapper,
            DataPackageStatMapper statMapper,
            CountryService countryService,
            DataPackageConverter converter) {
        this.mapper = mapper;
        this.phoneMapper = phoneMapper;
        this.statMapper = statMapper;
        this.countryService = countryService;
        this.converter = converter;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<DataPackageListItemVO> list(DataPackageQuery query) {
        DataPackageQuery normalized = query == null ? new DataPackageQuery() : query;
        normalizeListQuery(normalized);
        long total = mapper.countPage(normalized);
        if (total == 0) {
            return PageResult.of(List.of(), normalized.getPage(), normalized.getPageSize(), 0);
        }
        List<DataPackageListRow> rows = mapper.selectPage(normalized);
        Map<Long, List<String>> countries = currentCountries(
                rows.stream().map(DataPackageListRow::getId).toList());
        List<DataPackageListItemVO> items = rows.stream()
                .map(row -> converter.toListItem(
                        row, countries.getOrDefault(row.getId(), List.of())))
                .toList();
        return PageResult.of(items, normalized.getPage(), normalized.getPageSize(), total);
    }

    /** {@inheritDoc} */
    @Override
    public DataPackageDetailVO detail(Long id) {
        requirePositiveId(id);
        return toDetail(requireSummary(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DataPackageDetailVO create(DataPackageCreateDTO request, Long createdBy) {
        String name = requiredText(request == null ? null : request.name(), "数据包名称", NAME_MAX_LENGTH);
        String remark = optionalText(request == null ? null : request.remark(), "数据包备注", REMARK_MAX_LENGTH);
        long now = System.currentTimeMillis();
        DataPackage entity = new DataPackage();
        entity.setPackageName(name);
        entity.setRemark(remark);
        entity.setCurrentGeneration(1);
        entity.setPhoneCount(0);
        entity.setVersion(1);
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            mapper.insert(entity);
            statMapper.insertInitial(initialStat(entity.getId(), now));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据包名称已存在");
        }
        return toDetail(requireSummary(entity.getId()));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DataPackageDetailVO update(Long id, DataPackageUpdateDTO request) {
        requirePositiveId(id);
        String name = requiredText(request == null ? null : request.name(), "数据包名称", NAME_MAX_LENGTH);
        String remark = optionalText(request == null ? null : request.remark(), "数据包备注", REMARK_MAX_LENGTH);
        int version = requireVersion(request == null ? null : request.version());
        DataPackage locked = requireLocked(id);
        if (!Integer.valueOf(version).equals(locked.getVersion())) {
            throw versionConflict();
        }
        try {
            if (mapper.updateMetadata(id, name, remark, version, System.currentTimeMillis()) != 1) {
                throw versionConflict();
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据包名称已存在");
        }
        return toDetail(requireSummary(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requirePositiveId(id);
        requireLocked(id);
        if (mapper.softDelete(id, System.currentTimeMillis()) != 1) {
            throw notFound();
        }
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<DataPackagePhoneItemVO> phones(Long id, DataPackagePhoneQuery query) {
        requirePositiveId(id);
        DataPackage parent = mapper.selectActiveById(id);
        if (parent == null) {
            throw notFound();
        }
        DataPackagePhoneQuery normalized = query == null ? new DataPackagePhoneQuery() : query;
        normalizePhoneQuery(normalized, parent);
        long total = phoneMapper.countPage(normalized);
        if (total == 0) {
            return PageResult.of(List.of(), normalized.getPage(), normalized.getPageSize(), 0);
        }
        List<DataPackagePhoneItemVO> items = phoneMapper.selectPage(normalized).stream()
                .map(converter::toPhoneItem)
                .toList();
        return PageResult.of(items, normalized.getPage(), normalized.getPageSize(), total);
    }

    /** {@inheritDoc} */
    @Override
    public List<DataPackageCountryOptionVO> countries() {
        List<DataPackageCountryOptionVO> result = new ArrayList<>();
        for (CountryOptionVO country : countryService.options("marketing-export").rows()) {
            result.add(new DataPackageCountryOptionVO(
                    country.value(), country.iso2(), country.nameZh()));
        }
        result.add(new DataPackageCountryOptionVO(UNKNOWN_COUNTRY, null, "未识别"));
        return List.copyOf(result);
    }

    private DataPackageDetailVO toDetail(DataPackageListRow row) {
        return converter.toDetail(
                row, currentCountries(List.of(row.getId())).getOrDefault(row.getId(), List.of()));
    }

    private DataPackageListRow requireSummary(Long id) {
        DataPackageListRow row = mapper.selectSummaryById(id);
        if (row == null) {
            throw notFound();
        }
        return row;
    }

    private DataPackage requireLocked(Long id) {
        DataPackage entity = mapper.selectActiveForUpdate(id);
        if (entity == null) {
            throw notFound();
        }
        return entity;
    }

    private Map<Long, List<String>> currentCountries(List<Long> packageIds) {
        if (packageIds == null || packageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> mutable = new LinkedHashMap<>();
        for (DataPackageCountryRow row : mapper.selectCurrentCountries(packageIds)) {
            mutable.computeIfAbsent(row.getDataPackageId(), ignored -> new ArrayList<>())
                    .add(row.getCountryIso2());
        }
        Map<Long, List<String>> result = new LinkedHashMap<>();
        mutable.forEach((id, values) -> result.put(
                id, Collections.unmodifiableList(new ArrayList<>(values))));
        return Collections.unmodifiableMap(result);
    }

    private static void normalizeListQuery(DataPackageQuery query) {
        query.setName(optionalText(query.getName(), "数据包名称", NAME_MAX_LENGTH));
        if (query.getCreatedFrom() != null && query.getCreatedTo() != null
                && query.getCreatedTo() < query.getCreatedFrom()) {
            throw new BusinessException(ErrorCode.VALIDATION, "创建时间结束值不得小于开始值");
        }
        Set<String> values = new LinkedHashSet<>();
        if (StringUtils.hasText(query.getCountryIso2s())) {
            for (String part : query.getCountryIso2s().split(",", -1)) {
                if (!part.trim().isEmpty()) {
                    values.add(normalizeCountry(part));
                }
            }
        }
        query.setIncludeUnknownCountry(values.remove(UNKNOWN_COUNTRY));
        query.setRealCountryIso2s(List.copyOf(values));
    }

    private static void normalizePhoneQuery(DataPackagePhoneQuery query, DataPackage parent) {
        String phone = optionalText(query.getPhone(), "手机号", PHONE_MAX_LENGTH);
        if (phone != null && !PHONE_FILTER_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "手机号筛选只能输入数字");
        }
        DataPackagePoolStatus status = DataPackagePoolStatus.optionalFromApi(query.getPoolStatus());
        query.setPhone(phone);
        query.setPoolStatusCode(status == null ? null : status.code());
        query.setCountryIso2(StringUtils.hasText(query.getCountryIso2())
                ? normalizeCountry(query.getCountryIso2()) : null);
        query.setDataPackageId(parent.getId());
        query.setGeneration(parent.getCurrentGeneration());
    }

    private static String normalizeCountry(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!UNKNOWN_COUNTRY.equals(normalized) && !ISO2_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家筛选必须为 ISO2 或 UNKNOWN");
        }
        return normalized;
    }

    private static DataPackageStat initialStat(Long dataPackageId, long now) {
        DataPackageStat stat = new DataPackageStat();
        stat.setDataPackageId(dataPackageId);
        stat.setGeneration(1);
        stat.setUnusedCount(0);
        stat.setClaimedCount(0);
        stat.setSentCount(0);
        stat.setDeliveredCount(0);
        stat.setRetryableFailedCount(0);
        stat.setUnregisteredCount(0);
        stat.setUpdatedAt(now);
        return stat;
    }

    private static String requiredText(String value, String field, int maxLength) {
        String normalized = optionalText(value, field, maxLength);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.VALIDATION, field + "不能为空");
        }
        return normalized;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, field + "最长 " + maxLength + " 个字符");
        }
        return normalized;
    }

    private static int requireVersion(Integer version) {
        if (version == null || version <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "version 必须大于 0");
        }
        return version;
    }

    private static void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw notFound();
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "数据包不存在或已删除");
    }

    private static BusinessException versionConflict() {
        return new BusinessException(
                ErrorCode.CONFLICT, "数据包已被其他人修改，请刷新后重试");
    }
}
