package com.armada.marketing.service.impl;

import com.armada.marketing.converter.MarketingTemplateConverter;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.MessageButton;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.MarketingTemplateQuery;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import com.armada.marketing.service.MarketingTemplateService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 营销模板业务实现。
 *
 * <p>租户隔离由 MyBatis 租户拦截器透明完成,本类不手写 tenant_id。</p>
 */
@Service
public class MarketingTemplateServiceImpl implements MarketingTemplateService {

    private static final Logger log = LoggerFactory.getLogger(MarketingTemplateServiceImpl.class);

    /** 消息按钮上限。 */
    private static final int MAX_BUTTONS = 3;

    /** 复制模板的名称后缀。 */
    private static final String CLONE_SUFFIX = "副本";

    private final MarketingTemplateMapper mapper;
    private final MarketingTemplateConverter converter;

    public MarketingTemplateServiceImpl(MarketingTemplateMapper mapper, MarketingTemplateConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:先取总数再决定是否查列表——总数为 0 时直接返回空页,省掉一次必然空结果的
     * 列表查询;分页与筛选全部由 Mapper 的 SQL 下推,不在内存里裁剪。</p>
     */
    @Override
    public PageResult<MarketingTemplateVO> list(MarketingTemplateQuery query) {
        long total = mapper.countPage(query);
        List<MarketingTemplateVO> rows = total == 0
                ? List.of()
                : converter.toVOList(mapper.selectPage(query));
        log.debug("营销模板列表查询 total={} page={} pageSize={}", total, query.getPage(), query.getPageSize());
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:校验通过后由应用层写入 epoch 毫秒审计时间,再按主键回查一次来构建出参。
     * 全程 {@code @Transactional}:按钮 JSON 序列化或名称唯一键冲突时整体回滚。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTemplateVO create(MarketingTemplateDTO dto) {
        validate(dto, null);
        MarketingTemplate entity = converter.toEntity(dto);
        long now = System.currentTimeMillis();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        log.info("营销模板已创建 id={} name={} linkMode={}",
                entity.getId(), entity.getTemplateName(), entity.getLinkMode());
        return converter.toVO(mapper.selectById(entity.getId()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:先确认模板存在再校验;{@code id} 来自路径参数而非请求体,DTO 转换不携带它,
     * 须显式 {@code setId} 后 UPDATE 的 WHERE 才能命中目标行。更新后回查一次,
     * 让出参带上应用层写入的 {@code updated_at}。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTemplateVO update(Long id, MarketingTemplateDTO dto) {
        requireExisting(id);
        validate(dto, id);
        MarketingTemplate entity = converter.toEntity(dto);
        entity.setId(id);
        entity.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(entity);
        log.info("营销模板已更新 id={} name={}", id, entity.getTemplateName());
        return converter.toVO(mapper.selectById(id));
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:刻意逐字段复制业务列(不走 converter、也不整体拷贝实体),只搬模板配置;
     * {@code id}/{@code tenant_id}/创建人一律不带——这些由 INSERT 和租户拦截器在新行上生成。
     * 名称追加「{@value #CLONE_SUFFIX}」后缀并先查重,
     * 避免对同一模板连续复制产生同名冲突。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTemplateVO clone(Long id) {
        MarketingTemplate origin = requireExisting(id);
        String cloneName = origin.getTemplateName() + CLONE_SUFFIX;
        // excludeId 传 null:复制是新建,没有"自身"需要排除
        if (mapper.existsByName(cloneName, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "副本已存在,请先重命名后再复制");
        }
        MarketingTemplate copy = new MarketingTemplate();
        copy.setTemplateName(cloneName);
        copy.setLinkMode(origin.getLinkMode());
        copy.setTextType(origin.getTextType());
        copy.setImageFileId(origin.getImageFileId());
        copy.setContent(origin.getContent());
        copy.setBodyText(origin.getBodyText());
        copy.setButtons(origin.getButtons());
        copy.setPromotionLink(origin.getPromotionLink());
        copy.setRemark(origin.getRemark());
        long now = System.currentTimeMillis();
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        mapper.insert(copy);
        log.info("营销模板已复制 sourceId={} newId={} name={}", id, copy.getId(), cloneName);
        return converter.toVO(mapper.selectById(copy.getId()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:软删除(置 {@code deleted_at} 而非物理删行,保留历史与外键引用);
     * 空列表直接返回、不触库。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 跨域:被删模板关联的营销任务需停止——待 marketing/task 域建成后接入(当前一期模板先行)。
        mapper.softDeleteByIds(ids, System.currentTimeMillis());
        log.info("营销模板批量软删除 count={} ids={}", ids.size(), ids);
    }

    /** 按 ID 取未删模板,不存在即抛 404;update/clone 等写操作都先过这道存在性校验。 */
    private MarketingTemplate requireExisting(Long id) {
        MarketingTemplate entity = mapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板不存在: " + id);
        }
        return entity;
    }

    /**
     * 保存前统一校验:模板名/内容/文本必填、名称在租户内不重复、超链模式合法、按钮规则。
     *
     * @param excludeId 名称查重时要排除的 ID;新增传 {@code null},编辑传当前模板 ID 以放过自身
     */
    private void validate(MarketingTemplateDTO dto, Long excludeId) {
        if (!StringUtils.hasText(dto.templateName())) {
            throw new BusinessException(ErrorCode.VALIDATION, "模板名称不能为空");
        }
        if (!StringUtils.hasText(dto.content())) {
            throw new BusinessException(ErrorCode.VALIDATION, "内容不能为空");
        }
        if (!StringUtils.hasText(dto.bodyText())) {
            throw new BusinessException(ErrorCode.VALIDATION, "文本不能为空");
        }
        if (mapper.existsByName(dto.templateName(), excludeId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板名称已存在: " + dto.templateName());
        }
        LinkMode mode = LinkMode.fromCode(dto.linkMode());
        validateButtons(mode, dto.buttons());
    }

    /**
     * 按超链模式校验按钮。普通超链与按钮超链是互斥的两种消息形态:
     * 普通超链把链接当内容卡片展示、不得配按钮;按钮超链须由 1~3 个按钮承载,逐个按类型校验。
     */
    private void validateButtons(LinkMode mode, List<MessageButton> buttons) {
        boolean hasButtons = buttons != null && !buttons.isEmpty();
        if (mode == LinkMode.NORMAL) {
            if (hasButtons) {
                throw new BusinessException(ErrorCode.VALIDATION, "普通超链模式不可配置消息按钮");
            }
            return;
        }
        if (!hasButtons) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮超链模式至少配置 1 个消息按钮");
        }
        if (buttons.size() > MAX_BUTTONS) {
            throw new BusinessException(ErrorCode.VALIDATION, "消息按钮最多 " + MAX_BUTTONS + " 个");
        }
        for (MessageButton button : buttons) {
            validateButton(button);
        }
    }

    private void validateButton(MessageButton button) {
        if (button.type() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮类型不能为空");
        }
        if (!StringUtils.hasText(button.text())) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮文字不能为空");
        }
        // 快捷回复点击即回发按钮文字,无需 param;链接跳转(目标 URL)、复制内容(待复制文本)必须带 param
        if (button.type() != ButtonType.QUICK_REPLY && !StringUtils.hasText(button.param())) {
            throw new BusinessException(ErrorCode.VALIDATION, button.type() + " 按钮必须填写参数");
        }
    }
}
