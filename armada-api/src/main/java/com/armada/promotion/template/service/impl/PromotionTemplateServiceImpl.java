package com.armada.promotion.template.service.impl;

import com.armada.promotion.template.mapper.PromotionTemplateMapper;
import com.armada.promotion.template.model.dto.PromotionTemplateQuery;
import com.armada.promotion.template.model.vo.PromotionTemplateRow;
import com.armada.promotion.template.model.vo.PromotionTemplateSupportedParamVO;
import com.armada.promotion.template.model.vo.PromotionTemplateVO;
import com.armada.promotion.template.service.PromotionTemplateService;
import com.armada.shared.response.PageResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 模板分页实现；租户隔离仍由现有 MyBatis 拦截器统一处理。 */
@Service
public class PromotionTemplateServiceImpl implements PromotionTemplateService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() { };
    private static final Map<String, String> PARAM_LABELS = Map.of(
            "themeColor", "主题色",
            "showAppDownload", "展示底部应用下载");

    private final PromotionTemplateMapper mapper;
    private final ObjectMapper objectMapper;

    public PromotionTemplateServiceImpl(PromotionTemplateMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>分页和租户过滤全部下推数据库；支持参数在出参阶段把稳定代码转换为页面标签。</p>
    */
    @Override
    @Transactional(readOnly = true)
    public PageResult<PromotionTemplateVO> page(PromotionTemplateQuery query) {
        if (query == null) {
            query = new PromotionTemplateQuery();
        }

        // 步骤1：先统计总数，空页不再执行列表 SQL，避免一次无意义查询。
        long total = mapper.countPage(query);
        if (total == 0) {
            return PageResult.of(List.of(), query.getPage(), query.getPageSize(), 0);
        }

        // 步骤2：数据库完成租户过滤、有效状态过滤、倒序和 LIMIT 分页。
        List<PromotionTemplateRow> rows = mapper.selectPage(query);

        // 步骤3：把 JSON 参数代码转换成前端可以直接展示的 code + label，未知代码仍原样返回。
        List<PromotionTemplateVO> items = rows.stream().map(this::toVO).toList();
        return PageResult.of(items, query.getPage(), query.getPageSize(), total);
    }

    private PromotionTemplateVO toVO(PromotionTemplateRow row) {
        return new PromotionTemplateVO(
                row.getId(),
                row.getTemplateCode(),
                row.getTemplateName(),
                row.getPreviewUri(),
                row.getIsSubaccountVisible() != null && row.getIsSubaccountVisible() == 1,
                parseSupportedParams(row),
                row.getRemark(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    /** 解析数据库 JSON 数组，去除空值与重复代码并保持原配置顺序。 */
    private List<PromotionTemplateSupportedParamVO> parseSupportedParams(PromotionTemplateRow row) {
        if (!StringUtils.hasText(row.getSupportedParamsJson())) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(row.getSupportedParamsJson(), STRING_LIST_TYPE);
            LinkedHashSet<String> codes = new LinkedHashSet<>();
            for (String code : parsed) {
                if (StringUtils.hasText(code)) {
                    codes.add(code.trim());
                }
            }
            return codes.stream()
                    .map(code -> new PromotionTemplateSupportedParamVO(
                            code, PARAM_LABELS.getOrDefault(code, code)))
                    .toList();
        } catch (JsonProcessingException ex) {
            // JSON 列能保证语法合法，但不能保证一定是字符串数组；错误配置必须显式失败而不能静默吞掉。
            throw new IllegalStateException("模板支持参数配置非法，templateId=" + row.getId(), ex);
        }
    }
}
