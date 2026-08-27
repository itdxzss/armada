package com.armada.hyperlink.template.service.impl;

import com.armada.hyperlink.template.converter.HyperlinkTemplateConverter;
import com.armada.hyperlink.template.mapper.HyperlinkTemplateMapper;
import com.armada.hyperlink.template.model.HyperlinkMessageContent;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateCreateDTO;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateQuery;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateUpdateDTO;
import com.armada.hyperlink.template.model.entity.HyperlinkTemplate;
import com.armada.hyperlink.template.model.enums.HyperlinkMessageType;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateDetailVO;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateListItemVO;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateOptionVO;
import com.armada.hyperlink.template.service.HyperlinkMessageContentValidator;
import com.armada.hyperlink.template.service.HyperlinkTemplateService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 超链模板业务实现，事务边界覆盖名称并发保护、版本更新、复制和软删除。 */
@Service
public class HyperlinkTemplateServiceImpl implements HyperlinkTemplateService {

    /** 业务操作日志。 */
    private static final Logger log = LoggerFactory.getLogger(HyperlinkTemplateServiceImpl.class);
    /** 模板名称最大字符数。 */
    private static final int NAME_MAX_LENGTH = 128;
    /** 模板备注最大字符数。 */
    private static final int REMARK_MAX_LENGTH = 255;
    /** 候选关键词最大字符数。 */
    private static final int KEYWORD_MAX_LENGTH = 128;
    /** 候选默认返回上限。 */
    private static final int DEFAULT_OPTION_LIMIT = 50;
    /** 候选允许的最大返回上限。 */
    private static final int MAX_OPTION_LIMIT = 100;

    /** 超链模板数据访问。 */
    private final HyperlinkTemplateMapper mapper;
    /** 超链模板对象转换器。 */
    private final HyperlinkTemplateConverter converter;
    /** 共享消息内容校验器。 */
    private final HyperlinkMessageContentValidator contentValidator;

    public HyperlinkTemplateServiceImpl(
            HyperlinkTemplateMapper mapper,
            HyperlinkTemplateConverter converter,
            HyperlinkMessageContentValidator contentValidator) {
        this.mapper = mapper;
        this.converter = converter;
        this.contentValidator = contentValidator;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<HyperlinkTemplateListItemVO> list(HyperlinkTemplateQuery query) {
        normalizeListQuery(query);
        if (Integer.valueOf(HyperlinkMessageType.DOUBLE_IMAGE_TEXT.code()).equals(query.getMessageType())) {
            return PageResult.of(List.of(), query.getPage(), query.getPageSize(), 0);
        }
        long total = mapper.countPage(query);
        List<HyperlinkTemplateListItemVO> rows = total == 0
                ? List.of()
                : converter.toListItems(mapper.selectPage(query));
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /** {@inheritDoc} */
    @Override
    public HyperlinkTemplateDetailVO detail(Long id) {
        return converter.toDetail(requireExisting(id));
    }

    /** {@inheritDoc} */
    @Override
    public List<HyperlinkTemplateOptionVO> options(
            Integer messageType,
            String keyword,
            Integer limit) {
        if (messageType != null && !HyperlinkMessageType.fromCode(messageType).phaseOneSupported()) {
            throw new BusinessException(ErrorCode.VALIDATION, "一期暂不支持双图文");
        }
        String normalizedKeyword = optional(keyword, KEYWORD_MAX_LENGTH, "候选关键词最长 128 字符");
        int normalizedLimit = normalizeOptionLimit(limit);
        return converter.toOptions(mapper.selectOptions(messageType, normalizedKeyword, normalizedLimit));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public HyperlinkTemplateDetailVO create(HyperlinkTemplateCreateDTO request, long createdBy) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "模板内容不能为空");
        }
        String name = normalizeName(request.name());
        requireUniqueName(name, null);
        String remark = optional(request.remark(), REMARK_MAX_LENGTH, "备注最长 255 字符");
        HyperlinkMessageContent content = contentValidator.validateAndNormalize(converter.toContent(request));
        HyperlinkTemplate entity = converter.toEntity(name, remark, content);
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
        log.info("超链模板已创建 id={} messageType={}", entity.getId(), entity.getMessageType());
        return converter.toDetail(requireExisting(entity.getId()));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public HyperlinkTemplateDetailVO update(Long id, HyperlinkTemplateUpdateDTO request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "模板内容不能为空");
        }
        if (request.version() == null || request.version() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "version 必须为正整数");
        }
        requireExisting(id);
        String name = normalizeName(request.name());
        requireUniqueName(name, id);
        String remark = optional(request.remark(), REMARK_MAX_LENGTH, "备注最长 255 字符");
        HyperlinkMessageContent content = contentValidator.validateAndNormalize(converter.toContent(request));
        HyperlinkTemplate update = converter.toEntity(name, remark, content);
        update.setId(id);
        update.setUpdatedAt(System.currentTimeMillis());
        try {
            if (mapper.updateByIdAndVersion(update, request.version()) != 1) {
                throwUpdateConflict(id);
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateName();
        }
        log.info("超链模板已更新 id={} expectedVersion={}", id, request.version());
        return converter.toDetail(requireExisting(id));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public HyperlinkTemplateDetailVO copy(Long id, long createdBy) {
        HyperlinkTemplate origin = requireExisting(id);
        HyperlinkTemplate copy = converter.copyBusiness(origin);
        copy.setTemplateName(nextCopyName(origin.getTemplateName()));
        copy.setVersion(1);
        copy.setCreatedBy(createdBy);
        long now = System.currentTimeMillis();
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        try {
            mapper.insert(copy);
        } catch (DuplicateKeyException exception) {
            throw duplicateName();
        }
        log.info("超链模板已复制 sourceId={} newId={}", id, copy.getId());
        return converter.toDetail(requireExisting(copy.getId()));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireExisting(id);
        if (mapper.softDelete(id, System.currentTimeMillis()) != 1) {
            throw notFound();
        }
        log.info("超链模板已软删除 id={}", id);
    }

    private void normalizeListQuery(HyperlinkTemplateQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "查询参数不能为空");
        }
        query.setName(optional(query.getName(), NAME_MAX_LENGTH, "模板名称筛选最长 128 字符"));
        if (query.getMessageType() != null) {
            HyperlinkMessageType.fromCode(query.getMessageType());
        }
        if (query.getCreatedFrom() != null
                && query.getCreatedTo() != null
                && query.getCreatedTo() < query.getCreatedFrom()) {
            throw new BusinessException(ErrorCode.VALIDATION, "createdTo 不得小于 createdFrom");
        }
    }

    private HyperlinkTemplate requireExisting(Long id) {
        if (id == null || id <= 0) {
            throw notFound();
        }
        HyperlinkTemplate entity = mapper.selectById(id);
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

    private String nextCopyName(String sourceName) {
        for (int sequence = 1; ; sequence++) {
            String suffix = sequence == 1 ? " 副本" : " 副本 " + sequence;
            int baseLength = Math.min(sourceName.length(), NAME_MAX_LENGTH - suffix.length());
            String candidate = sourceName.substring(0, baseLength) + suffix;
            if (!mapper.existsByName(candidate, null)) {
                return candidate;
            }
        }
    }

    private void throwUpdateConflict(Long id) {
        if (mapper.selectById(id) == null) {
            throw notFound();
        }
        throw new BusinessException(ErrorCode.CONFLICT, "模板已被其他人修改，请刷新后重试");
    }

    private static String normalizeName(String value) {
        String normalized = optional(value, NAME_MAX_LENGTH, "模板名称最长 128 字符");
        if (normalized == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "模板名称不能为空");
        }
        return normalized;
    }

    private static int normalizeOptionLimit(Integer limit) {
        int normalized = limit == null ? DEFAULT_OPTION_LIMIT : limit;
        if (normalized < 1 || normalized > MAX_OPTION_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION, "limit 必须在 1 到 100 之间");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength, String lengthMessage) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, lengthMessage);
        }
        return normalized;
    }

    private static BusinessException duplicateName() {
        return new BusinessException(ErrorCode.CONFLICT, "模板名称已存在");
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "模板不存在或已删除");
    }
}
