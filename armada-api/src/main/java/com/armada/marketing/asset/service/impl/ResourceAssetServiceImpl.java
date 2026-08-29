package com.armada.marketing.asset.service.impl;

import com.armada.marketing.asset.converter.ResourceAssetConverter;
import com.armada.marketing.asset.mapper.ResourceAssetTagMapper;
import com.armada.marketing.asset.model.dto.ResourceAssetQuery;
import com.armada.marketing.asset.model.dto.ResourceAssetUpdateDTO;
import com.armada.marketing.asset.model.vo.ResourceAssetReferenceCountVO;
import com.armada.marketing.asset.model.vo.ResourceAssetTagRelationVO;
import com.armada.marketing.asset.model.vo.ResourceAssetTagsVO;
import com.armada.marketing.asset.model.vo.ResourceAssetVO;
import com.armada.marketing.asset.service.ResourceAssetImageValidator;
import com.armada.marketing.asset.service.ResourceAssetService;
import com.armada.marketing.asset.service.ResourceAssetTagNormalizer;
import com.armada.marketing.asset.service.ResourceAssetWriteService;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** 图片素材库业务实现；列表不读取图片 BLOB，上传校验完成后才进入写事务。 */
@Service
public class ResourceAssetServiceImpl implements ResourceAssetService {

    /** 素材名称最大字符数，与迁移列宽一致。 */
    private static final int MAX_NAME_LENGTH = 128;
    /** 管理页和选择器允许的分页档位。 */
    private static final Set<Integer> ALLOWED_PAGE_SIZES = Set.of(12, 24, 48, 96);
    /** multipart 标签 JSON 解析器；只解析字符串数组，不承载全局配置。 */
    private static final ObjectMapper TAGS_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 素材文件数据访问。 */
    private final MarketingTemplateFileMapper fileMapper;
    /** 素材标签数据访问。 */
    private final ResourceAssetTagMapper tagMapper;
    /** 复用既有鉴权图片内容读取能力。 */
    private final MarketingTemplateFileService fileService;
    /** 素材文件与标签关系的短事务写服务。 */
    private final ResourceAssetWriteService writeService;
    /** 素材实体与稳定 API 响应转换器。 */
    private final ResourceAssetConverter converter;

    /**
     * 创建图片素材库业务服务。
     *
     * @param fileMapper 素材文件数据访问
     * @param tagMapper 素材标签数据访问
     * @param fileService 既有图片内容服务
     * @param writeService 素材短事务写服务
     * @param converter 素材响应转换器
     */
    public ResourceAssetServiceImpl(
            MarketingTemplateFileMapper fileMapper,
            ResourceAssetTagMapper tagMapper,
            MarketingTemplateFileService fileService,
            ResourceAssetWriteService writeService,
            ResourceAssetConverter converter) {
        this.fileMapper = fileMapper;
        this.tagMapper = tagMapper;
        this.fileService = fileService;
        this.writeService = writeService;
        this.converter = converter;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<ResourceAssetVO> list(ResourceAssetQuery query) {
        normalizeQuery(query);
        long total = fileMapper.countAssetPage(query);
        List<MarketingTemplateFile> rows = total == 0 ? List.of() : fileMapper.selectAssetPage(query);
        List<ResourceAssetVO> list = assemble(rows);
        return PageResult.of(list, query.getPage(), query.getPageSize(), total);
    }

    /** {@inheritDoc} */
    @Override
    public ResourceAssetVO detail(Long id) {
        MarketingTemplateFile row = requireMetadata(id);
        return assemble(List.of(row)).get(0);
    }

    /** {@inheritDoc} */
    @Override
    public ResourceAssetTagsVO tags() {
        return new ResourceAssetTagsVO(tagMapper.selectActiveTagNames());
    }

    /** {@inheritDoc} */
    @Override
    public ResourceAssetVO upload(MultipartFile file, String tagsJson, long createdBy) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择图片");
        }
        ResourceAssetImageValidator.validateDeclaredSize(file.getSize());
        byte[] bytes = readBytes(file);
        String originalFilename = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename().trim()
                : "image.jpg";
        ResourceAssetImageValidator.Dimensions dimensions = ResourceAssetImageValidator.validateUpload(
                originalFilename, file.getContentType(), bytes);
        List<String> tags = parseTags(tagsJson);
        long now = System.currentTimeMillis();
        MarketingTemplateFile row = new MarketingTemplateFile();
        row.setOriginalFilename(originalFilename);
        row.setContentType(file.getContentType());
        row.setSizeBytes((long) bytes.length);
        row.setContent(bytes);
        row.setAssetName(truncate(originalFilename, MAX_NAME_LENGTH));
        row.setWidth(dimensions.width());
        row.setHeight(dimensions.height());
        row.setCreatedBy(createdBy);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        writeService.create(row, tags);
        return detail(row.getId());
    }

    /** {@inheritDoc} */
    @Override
    public ResourceAssetVO update(Long id, ResourceAssetUpdateDTO request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "素材信息不能为空");
        }
        String assetName = requiredName(request.assetName());
        List<String> tags = ResourceAssetTagNormalizer.normalize(request.tags());
        writeService.update(id, assetName, tags, System.currentTimeMillis());
        return detail(id);
    }

    /** {@inheritDoc} */
    @Override
    public void delete(Long id) {
        writeService.delete(id, System.currentTimeMillis());
    }

    /** {@inheritDoc} */
    @Override
    public MarketingTemplateFileContent content(Long id) {
        return fileService.content(id);
    }

    private List<ResourceAssetVO> assemble(List<MarketingTemplateFile> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(MarketingTemplateFile::getId).toList();
        Map<Long, List<String>> tagsByFile = new LinkedHashMap<>();
        for (ResourceAssetTagRelationVO relation : tagMapper.selectRelationsByFileIds(ids)) {
            tagsByFile.computeIfAbsent(relation.fileId(), ignored -> new ArrayList<>()).add(relation.tagName());
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        Map<Long, Long> referencesByFile = new HashMap<>();
        for (ResourceAssetReferenceCountVO reference : fileMapper.selectReferenceCounts(tenantId, ids)) {
            referencesByFile.put(reference.assetId(), reference.referenceCount());
        }
        return rows.stream().map(row -> converter.toVO(
                row,
                tagsByFile.getOrDefault(row.getId(), List.of()),
                referencesByFile.getOrDefault(row.getId(), 0L))).toList();
    }

    private MarketingTemplateFile requireMetadata(Long id) {
        if (id == null || id <= 0) {
            throw notFound();
        }
        MarketingTemplateFile row = fileMapper.selectAssetMetadataById(id);
        if (row == null) {
            throw notFound();
        }
        return row;
    }

    private void normalizeQuery(ResourceAssetQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "查询参数不能为空");
        }
        if (!ALLOWED_PAGE_SIZES.contains(query.getPageSize())) {
            throw new BusinessException(ErrorCode.VALIDATION, "pageSize 只接受 12、24、48、96");
        }
        String name = query.getAssetName();
        if (name != null) {
            name = name.trim();
            if (name.length() > MAX_NAME_LENGTH) {
                throw new BusinessException(ErrorCode.VALIDATION, "素材名称筛选最长 128 个字符");
            }
            query.setAssetName(name.isEmpty() ? null : name);
        }
        query.setTags(ResourceAssetTagNormalizer.normalize(query.getTags()));
    }

    private List<String> parseTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) {
            return List.of();
        }
        try {
            JsonNode root = TAGS_JSON.readTree(tagsJson);
            if (root == null || !root.isArray()) {
                throw invalidTagsJson();
            }
            List<String> tags = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isTextual()) {
                    throw invalidTagsJson();
                }
                tags.add(item.textValue());
            }
            return ResourceAssetTagNormalizer.normalize(tags);
        } catch (IOException exception) {
            throw invalidTagsJson();
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "图片读取失败");
        }
    }

    private static String requiredName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION, "素材名称不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION, "素材名称最长 128 个字符");
        }
        return normalized;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static BusinessException invalidTagsJson() {
        return new BusinessException(ErrorCode.VALIDATION, "tags 必须是 JSON 字符串数组");
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "图片不存在或已删除");
    }
}
