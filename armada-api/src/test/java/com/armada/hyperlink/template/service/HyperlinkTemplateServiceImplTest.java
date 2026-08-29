package com.armada.hyperlink.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.hyperlink.template.converter.HyperlinkTemplateConverter;
import com.armada.hyperlink.template.mapper.HyperlinkTemplateMapper;
import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateCreateDTO;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateQuery;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateUpdateDTO;
import com.armada.hyperlink.template.model.entity.HyperlinkTemplate;
import com.armada.hyperlink.template.model.enums.HyperlinkButtonType;
import com.armada.hyperlink.template.service.impl.HyperlinkTemplateServiceImpl;
import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.marketing.model.vo.MarketingTemplateFileVO;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.exception.BusinessException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.web.multipart.MultipartFile;

/** 超链模板 Service 元数据、版本、复制和错误语义测试。 */
class HyperlinkTemplateServiceImplTest {

    private FakeHyperlinkTemplateMapper mapper;
    private HyperlinkTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = new FakeHyperlinkTemplateMapper();
        HyperlinkTemplateConverter converter = Mappers.getMapper(HyperlinkTemplateConverter.class);
        MarketingTemplateFileService fileService = new NoAssetFileService();
        HyperlinkMessageContentValidator validator = new HyperlinkMessageContentValidator(fileService);
        service = new HyperlinkTemplateServiceImpl(mapper, converter, validator, fileService);
    }

    @Test
    void createTrimsNameAndRejectsDuplicateWithStableConflictMessage() {
        mapper.occupiedNames.add("福利模板");

        assertThatThrownBy(() -> service.create(createRequest("  福利模板  "), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模板名称已存在");
        assertThat(mapper.insertCalls).isZero();
    }

    @Test
    void createPersistsNormalizedContentAndCurrentCreator() {
        var detail = service.create(createRequest(" 新模板 "), 7L);

        HyperlinkTemplate entity = mapper.rows.get(detail.id());
        assertThat(detail.name()).isEqualTo("新模板");
        assertThat(detail.remark()).isEqualTo("备注");
        assertThat(detail.buttons()).singleElement().satisfies(button -> {
            assertThat(button.type()).isEqualTo(HyperlinkButtonType.CTA_URL);
            assertThat(button.displayText()).isEqualTo("立即查看");
        });
        assertThat(entity.getVersion()).isEqualTo(1);
        assertThat(entity.getCreatedBy()).isEqualTo(7L);
        assertThat(entity.getCreatedAt()).isPositive();
        assertThat(entity.getUpdatedAt()).isEqualTo(entity.getCreatedAt());
        assertThat(mapper.insertCalls).isEqualTo(1);
    }

    @Test
    void updateWithStaleVersionReturnsFrozenConflictMessage() {
        mapper.rows.put(301L, entity(301L, "旧模板"));
        mapper.updateResult = 0;

        assertThatThrownBy(() -> service.update(301L, updateRequest(1, "新模板")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模板已被其他人修改，请刷新后重试");
    }

    @Test
    void copyUsesIncrementingNameAndResetsAuditAndVersion() {
        mapper.rows.put(301L, entity(301L, "福利模板"));
        mapper.occupiedNames.add("福利模板 副本");

        var copied = service.copy(301L, 9L);

        HyperlinkTemplate copy = mapper.rows.get(copied.id());
        assertThat(copy.getTemplateName()).isEqualTo("福利模板 副本 2");
        assertThat(copy.getVersion()).isEqualTo(1);
        assertThat(copy.getCreatedBy()).isEqualTo(9L);
        assertThat(copy.getCreatedAt()).isPositive();
        assertThat(copy.getUpdatedAt()).isEqualTo(copy.getCreatedAt());
    }

    @Test
    void deleteMissingTemplateUsesFrozenNotFoundMessage() {
        assertThatThrownBy(() -> service.delete(404L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模板不存在或已删除");
        assertThat(mapper.softDeleteCalls).isZero();
    }

    @Test
    void listAllowsTypeTwoAsEmptyFilterButRejectsUnknownType() {
        HyperlinkTemplateQuery typeTwo = new HyperlinkTemplateQuery();
        typeTwo.setMessageType(2);
        assertThat(service.list(typeTwo).list()).isEmpty();
        assertThat(mapper.countCalls).isZero();

        HyperlinkTemplateQuery unknown = new HyperlinkTemplateQuery();
        unknown.setMessageType(9);
        assertThatThrownBy(() -> service.list(unknown))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("消息类型只支持");
    }

    @Test
    void optionsTrimsKeywordAndUsesDefaultLimit() {
        assertThat(service.options(3, "  福利  ", null)).isEmpty();

        assertThat(mapper.lastOptionMessageType).isEqualTo(3);
        assertThat(mapper.lastOptionKeyword).isEqualTo("福利");
        assertThat(mapper.lastOptionLimit).isEqualTo(50);
    }

    private static HyperlinkTemplateCreateDTO createRequest(String name) {
        return new HyperlinkTemplateCreateDTO(
                name, 1, 3, "标题", null, null, null,
                List.of(button()), null, null, null, " 备注 ");
    }

    private static HyperlinkTemplateUpdateDTO updateRequest(int version, String name) {
        return new HyperlinkTemplateUpdateDTO(
                version, name, 1, 3, "标题", null, null, null,
                List.of(button()), null, null, null, " 备注 ");
    }

    private static HyperlinkButton button() {
        return new HyperlinkButton(
                HyperlinkButtonType.CTA_URL,
                " 立即查看 ",
                " https://example.com/promo ",
                true,
                1);
    }

    private static HyperlinkTemplate entity(long id, String name) {
        HyperlinkTemplate entity = new HyperlinkTemplate();
        entity.setId(id);
        entity.setTemplateName(name);
        entity.setMessageType(3);
        entity.setMessageSchemaVersion(1);
        entity.setTitle("标题");
        entity.setButtons("[]");
        entity.setVersion(1);
        entity.setCreatedAt(100L);
        entity.setUpdatedAt(100L);
        return entity;
    }

    private static final class FakeHyperlinkTemplateMapper implements HyperlinkTemplateMapper {

        private final Map<Long, HyperlinkTemplate> rows = new HashMap<>();
        private final Set<String> occupiedNames = new HashSet<>();
        private long nextId = 301L;
        private int insertCalls;
        private int updateResult = 1;
        private int softDeleteCalls;
        private int countCalls;
        private Integer lastOptionMessageType;
        private String lastOptionKeyword;
        private int lastOptionLimit;

        @Override
        public long countPage(HyperlinkTemplateQuery query) {
            countCalls++;
            return rows.size();
        }

        @Override
        public List<HyperlinkTemplate> selectPage(HyperlinkTemplateQuery query) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public HyperlinkTemplate selectById(Long id) {
            return rows.get(id);
        }

        @Override
        public List<HyperlinkTemplate> selectOptions(Integer messageType, String keyword, int limit) {
            lastOptionMessageType = messageType;
            lastOptionKeyword = keyword;
            lastOptionLimit = limit;
            return List.of();
        }

        @Override
        public boolean existsByName(String name, Long excludeId) {
            if (occupiedNames.contains(name)) {
                return true;
            }
            return rows.values().stream().anyMatch(row -> row.getTemplateName().equals(name)
                    && (excludeId == null || !row.getId().equals(excludeId)));
        }

        @Override
        public int insert(HyperlinkTemplate entity) {
            insertCalls++;
            if (entity.getId() == null) {
                while (rows.containsKey(nextId)) {
                    nextId++;
                }
                entity.setId(nextId++);
            }
            rows.put(entity.getId(), entity);
            occupiedNames.add(entity.getTemplateName());
            return 1;
        }

        @Override
        public int updateByIdAndVersion(HyperlinkTemplate entity, int expectedVersion) {
            return updateResult;
        }

        @Override
        public int softDelete(Long id, long deletedAt) {
            softDeleteCalls++;
            return rows.remove(id) == null ? 0 : 1;
        }
    }

    private static final class NoAssetFileService implements MarketingTemplateFileService {

        @Override
        public MarketingTemplateFileVO uploadImage(MultipartFile file) {
            throw new UnsupportedOperationException("测试不执行上传");
        }

        @Override
        public MarketingTemplateFileContent content(Long id) {
            throw new UnsupportedOperationException("测试请求不绑定图片");
        }

        @Override
        public MarketingTemplateFileContent lockContentForBinding(Long id) {
            throw new UnsupportedOperationException("测试请求不绑定图片");
        }

        @Override
        public void lockAndValidateBindableAssets(java.util.Collection<Long> ids) {
            throw new UnsupportedOperationException("测试请求不绑定图片");
        }
    }
}
