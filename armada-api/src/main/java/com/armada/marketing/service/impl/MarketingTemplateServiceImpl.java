package com.armada.marketing.service.impl;

import com.armada.marketing.converter.MarketingTemplateConverter;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.mapper.MarketingTaskMapper;
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
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.util.HttpUrlValidator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 营销模板业务实现。
 *
 * <p>普通查询由 MyBatis 租户拦截器透明隔离；批量删除的锁行查询为避免 SQL 改写破坏
 * MySQL 锁子句顺序，由本类从租户上下文传递 tenantId 并在 Mapper SQL 中显式限定。</p>
 */
@Service
public class MarketingTemplateServiceImpl implements MarketingTemplateService {

    private static final Logger log = LoggerFactory.getLogger(MarketingTemplateServiceImpl.class);

    /** 消息按钮上限。 */
    private static final int MAX_BUTTONS = 3;

    /** 复制模板的名称后缀。 */
    private static final String CLONE_SUFFIX = "副本";

    private final MarketingTemplateMapper mapper;
    private final MarketingTaskMapper taskMapper;
    private final MarketingTemplateConverter converter;
    private final MarketingAccountOccupancyService occupancyService;

    public MarketingTemplateServiceImpl(MarketingTemplateMapper mapper,
                                        MarketingTaskMapper taskMapper,
                                        MarketingTemplateConverter converter,
                                        MarketingAccountOccupancyService occupancyService) {
        this.mapper = mapper;
        this.taskMapper = taskMapper;
        this.converter = converter;
        this.occupancyService = occupancyService;
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
        LinkMode mode = validate(dto, null);
        MarketingTemplate entity = converter.toEntity(dto);
        normalizeByMode(entity, mode);
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
        LinkMode mode = validate(dto, id);
        MarketingTemplate entity = converter.toEntity(dto);
        normalizeByMode(entity, mode);
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
        copy.setMentionAll(origin.getMentionAll());
        normalizeByMode(copy, LinkMode.fromCode(copy.getLinkMode()));
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
     * <p>实现要点：同一事务内先按固定顺序锁定未删除模板，再把仍占用账号的关联任务按异常终止
     * 置为已完成并释放账号，最后软删除模板；已有终态任务保留历史状态。空列表直接返回、不触库。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) {
        List<Long> normalizedIds = ids == null
                ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (normalizedIds.isEmpty()) {
            return;
        }
        // 必须先锁模板再扫描关联任务：如果创建任务先拿到锁，本事务等待后可以看到并结束新任务；
        // 如果删除先拿到锁，创建事务会在软删除提交后查不到模板，从而整体回滚。
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        List<Long> lockedTemplateIds = mapper.selectExistingIdsForUpdate(tenantId, normalizedIds);
        if (taskMapper.countActiveGroupPullTasksByTemplateIds(normalizedIds) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板正在被拉群营销任务使用，不能删除");
        }
        long now = System.currentTimeMillis();
        int completedTasks = taskMapper.completeActiveTasksByTemplateIds(normalizedIds, now);
        int releasedAccounts = occupancyService.releaseAccountsByTemplateIds(normalizedIds);
        mapper.softDeleteByIds(normalizedIds, now);
        log.info("营销模板批量软删除 requested={} locked={} abnormalCompletedTasks={} releasedAccounts={} ids={}",
                normalizedIds.size(), lockedTemplateIds.size(), completedTasks, releasedAccounts, normalizedIds);
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
     * 保存前统一校验:模板名/内容必填、名称在租户内不重复、消息类型合法、按钮规则。
     *
     * @param excludeId 名称查重时要排除的 ID;新增传 {@code null},编辑传当前模板 ID 以放过自身
     */
    private LinkMode validate(MarketingTemplateDTO dto, Long excludeId) {
        if (!StringUtils.hasText(dto.templateName())) {
            throw new BusinessException(ErrorCode.VALIDATION, "模板名称不能为空");
        }
        if (!StringUtils.hasText(dto.content())) {
            throw new BusinessException(ErrorCode.VALIDATION, "内容不能为空");
        }
        if (mapper.existsByName(dto.templateName(), excludeId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板名称已存在: " + dto.templateName());
        }
        LinkMode mode = LinkMode.fromCode(dto.linkMode());
        if (mode != LinkMode.BUTTON
                && StringUtils.hasText(dto.promotionLink())
                && !HttpUrlValidator.isHttpUrl(dto.promotionLink())) {
            throw new BusinessException(ErrorCode.VALIDATION, "推广链接格式不正确,必须是以 http(s):// 开头的合法链接");
        }
        validateButtons(mode, dto.buttons());
        return mode;
    }

    /**
     * 按消息类型归一化不生效的字段。按钮模式的跳转链接只来自按钮配置,历史 promotion_link 不再参与保存。
     */
    private static void normalizeByMode(MarketingTemplate entity, LinkMode mode) {
        if (mode == LinkMode.BUTTON) {
            entity.setPromotionLink(null);
        }
    }

    /**
     * 按消息类型校验按钮。只有按钮超链允许配置按钮;普通超链和图文内容都不携带按钮。
     */
    private void validateButtons(LinkMode mode, List<MessageButton> buttons) {
        boolean hasButtons = buttons != null && !buttons.isEmpty();
        if (mode != LinkMode.BUTTON) {
            if (hasButtons) {
                String modeName = mode == LinkMode.IMAGE_TEXT ? "图文内容消息类型" : "普通超链消息类型";
                throw new BusinessException(ErrorCode.VALIDATION, modeName + "不可配置消息按钮");
            }
            return;
        }
        if (!hasButtons) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮超链消息类型至少配置 1 个消息按钮");
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
        if (button.type() == ButtonType.LINK_JUMP && !HttpUrlValidator.isHttpUrl(button.param())) {
            throw new BusinessException(ErrorCode.VALIDATION, "跳转链接格式不正确,必须是以 http(s):// 开头的合法链接");
        }
    }
}
