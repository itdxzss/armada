package com.armada.hyperlink.strategy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.hyperlink.strategy.converter.HyperlinkStrategyConverter;
import com.armada.hyperlink.strategy.mapper.HyperlinkStrategyMapper;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyCreateDTO;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyQuery;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyUpdateDTO;
import com.armada.hyperlink.strategy.model.entity.HyperlinkStrategy;
import com.armada.hyperlink.strategy.service.impl.HyperlinkStrategyServiceImpl;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** 超链策略参数边界、归一化、名称冲突和乐观锁错误语义测试。 */
class HyperlinkStrategyServiceImplTest {

    private FakeMapper mapper;
    private HyperlinkStrategyServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        mapper = new FakeMapper();
        HyperlinkStrategyConverter converter = Mappers.getMapper(HyperlinkStrategyConverter.class);
        HyperlinkStrategySnapshotCodec codec = new HyperlinkStrategySnapshotCodec(
                new ObjectMapper(), new com.armada.hyperlink.task.service.HyperlinkAccountFilterNormalizer());
        service = new HyperlinkStrategyServiceImpl(
                mapper, converter, codec, new NoopAccountContextService());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createNormalizesNameFilterAndNonCycleInterval() {
        HyperlinkAccountFilterDTO filter = filter(
                List.of("br", "BR"), List.of(9L, 3L, 9L));
        var detail = service.create(new HyperlinkStrategyCreateDTO(
                "  巴西即时  ", "instant", filter, 10, 0, 0, 60, true), 21L);

        HyperlinkStrategy stored = mapper.rows.get(detail.id());
        assertThat(detail.name()).isEqualTo("巴西即时");
        assertThat(detail.taskMode()).isEqualTo("instant");
        assertThat(detail.accountFilter().countryIso2s()).containsExactly("BR");
        assertThat(detail.accountFilter().groupIds()).containsExactly(3L, 9L);
        assertThat(detail.cycleIntervalMinutes()).isZero();
        assertThat(stored.getTaskIntervalMinutes()).isZero();
        assertThat(stored.getConcurrentNum()).isEqualTo(10);
        assertThat(stored.getStrategyScope()).isEqualTo(1);
        assertThat(stored.getCreatedBy()).isEqualTo(21L);
        assertThat(stored.getVersion()).isEqualTo(1);
    }

    @Test
    void maxExecutingAccountsAcceptsAutoZeroAndRejectsValuesOutsideRange() {
        assertThat(service.create(createRequest("auto", "instant", 0, 0, 0), 1L)
                .maxExecutingAccounts()).isZero();

        assertThatThrownBy(() -> service.create(createRequest("negative", "instant", -1, 0, 0), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("maxExecutingAccounts 必须在 0 到 100 之间，0 表示自动均分");

        assertThatThrownBy(() -> service.create(createRequest("too-many", "instant", 101, 0, 0), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("maxExecutingAccounts 必须在 0 到 100 之间，0 表示自动均分");
    }

    @Test
    void cycleRequiresThirtyMinutesAndPositivePerRoundAccountLimit() {
        assertThatThrownBy(() -> service.create(createRequest("short", "cycle", 10, 50, 29), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不得小于 30 分钟");

        assertThatThrownBy(() -> service.create(createRequest("unbounded", "cycle", 10, 0, 60), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("每轮最大账号数必须大于 0");

        assertThatThrownBy(() -> service.create(createRequest("below-concurrency", "cycle", 10, 9, 60), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("maxUseAccounts 大于 0 时不得小于 maxExecutingAccounts");
    }

    @Test
    void duplicateNameAndStaleUpdateUseStableConflictMessages() {
        service.create(createRequest("同名", "instant", 10, 0, 0), 1L);

        assertThatThrownBy(() -> service.create(createRequest("  同名  ", "rolling", 10, 0, 0), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("策略名称已存在");

        HyperlinkStrategy existing = mapper.rows.values().iterator().next();
        mapper.forceStaleUpdate = true;
        assertThatThrownBy(() -> service.update(existing.getId(), new HyperlinkStrategyUpdateDTO(
                1, "新名", "instant", emptyFilter(), 10, 0, 0, 0, false)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("策略已被其他人修改，请刷新后重试");
    }

    @Test
    void optionsTrimKeywordUseDefaultLimitAndReturnWeakCopyFields() {
        service.create(createRequest("巴西策略", "instant", 10, 0, 0), 1L);

        assertThat(service.options("  巴西  ", null))
                .singleElement()
                .satisfies(option -> {
                    assertThat(option.name()).isEqualTo("巴西策略");
                    assertThat(option.taskMode()).isEqualTo("instant");
                    assertThat(option.maxExecutingAccounts()).isEqualTo(10);
                });
        assertThat(mapper.lastOptionKeyword).isEqualTo("巴西");
        assertThat(mapper.lastOptionLimit).isEqualTo(50);
    }

    private static HyperlinkStrategyCreateDTO createRequest(
            String name, String mode, int maxExecuting, int maxUse, int cycleInterval) {
        return new HyperlinkStrategyCreateDTO(
                name, mode, emptyFilter(), maxExecuting, maxUse, 0, cycleInterval, true);
    }

    private static HyperlinkAccountFilterDTO emptyFilter() {
        return filter(List.of(), List.of());
    }

    private static HyperlinkAccountFilterDTO filter(
            List<String> countries, List<Long> groupIds) {
        return new HyperlinkAccountFilterDTO(
                1, countries, List.of(), null, groupIds, List.of(), null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private static final class FakeMapper implements HyperlinkStrategyMapper {

        private final Map<Long, HyperlinkStrategy> rows = new HashMap<>();
        private long nextId = 301L;
        private boolean forceStaleUpdate;
        private String lastOptionKeyword;
        private int lastOptionLimit;

        @Override
        public long countPage(HyperlinkStrategyQuery query, Integer taskType) {
            return rows.size();
        }

        @Override
        public List<HyperlinkStrategy> selectPage(HyperlinkStrategyQuery query, Integer taskType) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public HyperlinkStrategy selectById(Long id) {
            return rows.get(id);
        }

        @Override
        public List<HyperlinkStrategy> selectOptions(String keyword, int limit) {
            lastOptionKeyword = keyword;
            lastOptionLimit = limit;
            return rows.values().stream()
                    .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                    .filter(row -> keyword == null || row.getStrategyName().contains(keyword))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean existsByName(String name, Long excludeId) {
            return rows.values().stream().anyMatch(row -> row.getStrategyName().equals(name)
                    && (excludeId == null || !row.getId().equals(excludeId)));
        }

        @Override
        public int insert(HyperlinkStrategy entity) {
            entity.setId(nextId++);
            rows.put(entity.getId(), entity);
            return 1;
        }

        @Override
        public int updateByIdAndVersion(HyperlinkStrategy entity, int expectedVersion) {
            HyperlinkStrategy existing = rows.get(entity.getId());
            if (forceStaleUpdate || existing == null || existing.getVersion() != expectedVersion) {
                return 0;
            }
            entity.setVersion(expectedVersion + 1);
            entity.setCreatedBy(existing.getCreatedBy());
            entity.setCreatedAt(existing.getCreatedAt());
            rows.put(entity.getId(), entity);
            return 1;
        }

        @Override
        public int softDelete(Long id, long deletedAt) {
            return rows.remove(id) == null ? 0 : 1;
        }

        @Override
        public HyperlinkStrategy selectTaskSnapshotByOwner(long taskId) {
            return null;
        }

        @Override
        public int attachTaskOwner(long id, long taskId, long updatedAt) {
            return 0;
        }

        @Override
        public int updateTaskSnapshot(HyperlinkStrategy entity, long taskId) {
            return 0;
        }
    }

    private static final class NoopAccountContextService
            extends HyperlinkStrategyAccountContextService {

        private NoopAccountContextService() {
            super(null, null, null, null, null);
        }
    }
}
