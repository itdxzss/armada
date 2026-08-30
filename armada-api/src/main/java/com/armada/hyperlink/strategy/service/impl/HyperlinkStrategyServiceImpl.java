package com.armada.hyperlink.strategy.service.impl;

import com.armada.hyperlink.strategy.converter.HyperlinkStrategyConverter;
import com.armada.hyperlink.strategy.mapper.HyperlinkStrategyMapper;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyCreateDTO;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyQuery;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyUpdateDTO;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyWriteDTO;
import com.armada.hyperlink.strategy.model.entity.HyperlinkStrategy;
import com.armada.hyperlink.strategy.model.enums.HyperlinkStrategyScope;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyAccountContextVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyDetailVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyListItemVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyOptionVO;
import com.armada.hyperlink.strategy.service.HyperlinkStrategyAccountContextService;
import com.armada.hyperlink.strategy.service.HyperlinkStrategyService;
import com.armada.hyperlink.strategy.service.HyperlinkStrategySnapshotCodec;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountMatchCountVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 超链策略校验、租户内唯一性、乐观锁和软删除实现。 */
@Service
public class HyperlinkStrategyServiceImpl implements HyperlinkStrategyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HyperlinkStrategyServiceImpl.class);
    private static final int NAME_MAX_LENGTH = 128;
    private static final int MAX_EXECUTING_ACCOUNTS = 100;
    private static final int MIN_CYCLE_INTERVAL_MINUTES = 30;
    private static final int DEFAULT_OPTION_LIMIT = 50;
    private static final int MAX_OPTION_LIMIT = 100;

    private final HyperlinkStrategyMapper mapper;
    private final HyperlinkStrategyConverter converter;
    private final HyperlinkStrategySnapshotCodec snapshotCodec;
    private final HyperlinkStrategyAccountContextService accountContextService;

    public HyperlinkStrategyServiceImpl(
            HyperlinkStrategyMapper mapper,
            HyperlinkStrategyConverter converter,
            HyperlinkStrategySnapshotCodec snapshotCodec,
            HyperlinkStrategyAccountContextService accountContextService) {
        this.mapper = mapper;
        this.converter = converter;
        this.snapshotCodec = snapshotCodec;
        this.accountContextService = accountContextService;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<HyperlinkStrategyListItemVO> list(HyperlinkStrategyQuery query) {
        requireTenant();
        if (query == null) {
            throw validation("查询参数不能为空");
        }
        query.setName(optionalText(query.getName(), NAME_MAX_LENGTH, "策略名称筛选最长 128 字符")
                .orElse(null));
        Integer taskType = query.getTaskMode() == null || query.getTaskMode().isBlank()
                ? null : HyperlinkTaskMode.fromApi(query.getTaskMode().trim()).code();
        long total = mapper.countPage(query, taskType);
        List<HyperlinkStrategyListItemVO> rows = total == 0
                ? List.of()
                : mapper.selectPage(query, taskType).stream().map(this::toListItem).toList();
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /** {@inheritDoc} */
    @Override
    public HyperlinkStrategyDetailVO detail(Long id) {
        requireTenant();
        return toDetail(requireExisting(id));
    }

    /** {@inheritDoc} */
    @Override
    public List<HyperlinkStrategyOptionVO> options(String keyword, Integer limit) {
        requireTenant();
        String normalizedKeyword = optionalText(
                keyword, NAME_MAX_LENGTH, "候选关键词最长 128 字符").orElse(null);
        int normalizedLimit = normalizeOptionLimit(limit);
        return mapper.selectOptions(normalizedKeyword, normalizedLimit).stream()
                .map(this::toOption)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public HyperlinkStrategyDetailVO create(HyperlinkStrategyCreateDTO request, long createdBy) {
        requireTenant();
        Normalized normalized = normalize(request);
        requireUniqueName(normalized.name(), null);
        HyperlinkStrategyCreateDTO value = normalized.createRequest();
        HyperlinkStrategy entity = converter.toEntity(
                value, normalized.mode().code(), normalized.filter().json());
        entity.setStrategyScope(HyperlinkStrategyScope.TEMPLATE.code());
        long now = System.currentTimeMillis();
        entity.setVersion(1);
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw duplicateName();
        }
        LOGGER.info("超链策略已创建 id={} taskMode={}", entity.getId(), normalized.mode().api());
        return toDetail(requireExisting(entity.getId()));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public HyperlinkStrategyDetailVO update(Long id, HyperlinkStrategyUpdateDTO request) {
        requireTenant();
        if (request == null || request.version() == null || request.version() < 1) {
            throw validation("version 必须为正整数");
        }
        requireExisting(id);
        Normalized normalized = normalize(request);
        requireUniqueName(normalized.name(), id);
        HyperlinkStrategyUpdateDTO value = normalized.updateRequest(request.version());
        HyperlinkStrategy entity = converter.toEntity(
                value, normalized.mode().code(), normalized.filter().json());
        entity.setId(id);
        entity.setUpdatedAt(System.currentTimeMillis());
        try {
            if (mapper.updateByIdAndVersion(entity, request.version()) != 1) {
                throwUpdateConflict(id);
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateName();
        }
        LOGGER.info("超链策略已更新 id={} expectedVersion={}", id, request.version());
        return toDetail(requireExisting(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireTenant();
        requireExisting(id);
        if (mapper.softDelete(id, System.currentTimeMillis()) != 1) {
            throw notFound();
        }
        LOGGER.info("超链策略已软删除 id={}", id);
    }

    /** {@inheritDoc} */
    @Override
    public HyperlinkStrategyAccountContextVO accountContext() {
        return accountContextService.context();
    }

    /** {@inheritDoc} */
    @Override
    public HyperlinkAccountMatchCountVO accountMatchCount(HyperlinkAccountFilterDTO filter) {
        return accountContextService.matchCount(filter);
    }

    private Normalized normalize(HyperlinkStrategyWriteDTO request) {
        if (request == null || request.enabled() == null) {
            throw validation("策略内容或 enabled 非法");
        }
        String name = normalizeName(request.name());
        HyperlinkTaskMode mode = HyperlinkTaskMode.fromApi(request.taskMode());
        int maxExecuting = boundedExecuting(request.maxExecutingAccounts());
        int maxUse = nonNegative(request.maxUseAccounts(), "maxUseAccounts 不能小于 0");
        int maxSend = nonNegative(request.maxSendPerAccount(), "maxSendPerAccount 不能小于 0");
        if (maxUse > 0 && maxExecuting > 0 && maxUse < maxExecuting) {
            throw validation("maxUseAccounts 大于 0 时不得小于 maxExecutingAccounts");
        }
        int cycleInterval = normalizeCycle(mode, request.cycleIntervalMinutes(), maxUse);
        HyperlinkStrategySnapshotCodec.Encoded filter = snapshotCodec.encode(request.accountFilter());
        return new Normalized(
                name, mode, filter, maxExecuting, maxUse, maxSend, cycleInterval,
                request.enabled());
    }

    private int normalizeCycle(HyperlinkTaskMode mode, Integer interval, int maxUse) {
        if (mode != HyperlinkTaskMode.CYCLE) {
            return 0;
        }
        if (interval == null || interval < MIN_CYCLE_INTERVAL_MINUTES || maxUse < 1) {
            throw validation("周期策略间隔不得小于 30 分钟且每轮最大账号数必须大于 0");
        }
        return interval;
    }

    private HyperlinkStrategy requireExisting(Long id) {
        if (id == null || id < 1) {
            throw notFound();
        }
        HyperlinkStrategy entity = mapper.selectById(id);
        if (entity == null) {
            throw notFound();
        }
        return entity;
    }

    private void requireUniqueName(String name, Long excludeId) {
        if (mapper.existsByName(name, excludeId)) {
            throw duplicateName();
        }
    }

    private void throwUpdateConflict(Long id) {
        if (mapper.selectById(id) == null) {
            throw notFound();
        }
        throw new BusinessException(ErrorCode.CONFLICT, "策略已被其他人修改，请刷新后重试");
    }

    private HyperlinkStrategyDetailVO toDetail(HyperlinkStrategy entity) {
        return converter.toDetail(
                entity, mode(entity.getTaskType()).api(), snapshotCodec.decode(entity.getAccountFilter()));
    }

    private HyperlinkStrategyListItemVO toListItem(HyperlinkStrategy entity) {
        return converter.toListItem(
                entity, mode(entity.getTaskType()).api(), snapshotCodec.decode(entity.getAccountFilter()));
    }

    private HyperlinkStrategyOptionVO toOption(HyperlinkStrategy entity) {
        return converter.toOption(
                entity, mode(entity.getTaskType()).api(), snapshotCodec.decode(entity.getAccountFilter()));
    }

    private static HyperlinkTaskMode mode(Integer code) {
        for (HyperlinkTaskMode mode : HyperlinkTaskMode.values()) {
            if (Integer.valueOf(mode.code()).equals(code)) {
                return mode;
            }
        }
        throw validation("策略任务模式快照非法");
    }

    private static int boundedExecuting(Integer value) {
        if (value == null || value < 0 || value > MAX_EXECUTING_ACCOUNTS) {
            throw validation("maxExecutingAccounts 必须在 0 到 100 之间，0 表示自动均分");
        }
        return value;
    }

    private static int nonNegative(Integer value, String message) {
        if (value == null || value < 0) {
            throw validation(message);
        }
        return value;
    }

    private static String normalizeName(String value) {
        return optionalText(value, NAME_MAX_LENGTH, "策略名称最长 128 字符")
                .orElseThrow(() -> validation("策略名称不能为空"));
    }

    private static Optional<String> optionalText(
            String value, int maxLength, String lengthMessage) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.length() > maxLength) {
            throw validation(lengthMessage);
        }
        return Optional.of(normalized);
    }

    private static int normalizeOptionLimit(Integer limit) {
        int normalized = limit == null ? DEFAULT_OPTION_LIMIT : limit;
        if (normalized < 1 || normalized > MAX_OPTION_LIMIT) {
            throw validation("limit 必须在 1 到 100 之间");
        }
        return normalized;
    }

    private static void requireTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId < 1) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
    }

    private static BusinessException duplicateName() {
        return new BusinessException(ErrorCode.CONFLICT, "策略名称已存在");
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "策略不存在或已删除");
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private record Normalized(
            String name,
            HyperlinkTaskMode mode,
            HyperlinkStrategySnapshotCodec.Encoded filter,
            int maxExecuting,
            int maxUse,
            int maxSend,
            int cycleInterval,
            boolean enabled) {

        HyperlinkStrategyCreateDTO createRequest() {
            return new HyperlinkStrategyCreateDTO(
                    name, mode.api(), filter.value(), maxExecuting, maxUse, maxSend,
                    cycleInterval, enabled);
        }

        HyperlinkStrategyUpdateDTO updateRequest(int version) {
            return new HyperlinkStrategyUpdateDTO(
                    version, name, mode.api(), filter.value(), maxExecuting, maxUse, maxSend,
                    cycleInterval, enabled);
        }
    }
}
