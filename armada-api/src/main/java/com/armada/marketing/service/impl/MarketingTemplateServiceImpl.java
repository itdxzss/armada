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
import com.armada.marketing.service.MarketingTemplateFileService;
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

    /** 营销模板业务日志，不记录图片字节或认证信息。 */
    private static final Logger log = LoggerFactory.getLogger(MarketingTemplateServiceImpl.class);

    /** 消息按钮上限。 */
    private static final int MAX_BUTTONS = 3;

    /** 复制模板的名称后缀。 */
    private static final String CLONE_SUFFIX = "副本";

    /** 营销模板分页、持久化和锁行数据访问。 */
    private final MarketingTemplateMapper mapper;

    /** 营销任务引用检查与异常终止数据访问。 */
    private final MarketingTaskMapper taskMapper;

    /** 营销模板 DTO、实体和响应转换器。 */
    private final MarketingTemplateConverter converter;

    /** 营销任务异常终止后的账号占用释放服务。 */
    private final MarketingAccountOccupancyService occupancyService;

    /** 模板写入前锁定并复核关联素材。 */
    private final MarketingTemplateFileService fileService;

    /**
     * 创建营销模板业务服务。
     *
     * @param mapper 营销模板数据访问
     * @param taskMapper 营销任务引用数据访问
     * @param converter 营销模板 DTO、实体与响应转换器
     * @param occupancyService 营销账号占用管理服务
     * @param fileService 素材行锁与字节校验服务
     */
    public MarketingTemplateServiceImpl(
            MarketingTemplateMapper mapper,
            MarketingTaskMapper taskMapper,
            MarketingTemplateConverter converter,
            MarketingAccountOccupancyService occupancyService,
            MarketingTemplateFileService fileService) {
        this.mapper = mapper;
        this.taskMapper = taskMapper;
        this.converter = converter;
        this.occupancyService = occupancyService;
        this.fileService = fileService;
    }

    /**
     * 分页查询当前租户的营销模板。
     *
     * <p>支持 ID、模板名称、文本类型和消息类型组合筛选。先查询总数，总数为零时直接返回空页；
     * 分页与筛选全部由 Mapper SQL 下推，不在内存中裁剪。</p>
     *
     * @param query 营销模板筛选和分页参数
     * @return 当前页营销模板及总数
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
     * 创建供营销任务引用的营销模板。
     *
     * <p>校验模板名称、内容、消息类型、推广链接和按钮规则后，按消息模式清理不生效字段；
     * 若绑定图片，则在同一事务内锁定并校验素材。按钮序列化、素材校验或持久化失败时整体回滚。</p>
     *
     * @param dto 营销模板配置
     * @return 创建后的营销模板
     * @throws BusinessException 参数不合法、名称已存在或绑定素材不可用时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTemplateVO create(MarketingTemplateDTO dto) {
        LinkMode mode = validate(dto, null);
        MarketingTemplate entity = converter.toEntity(dto);
        normalizeByMode(entity, mode);
        lockImage(entity.getImageFileId());
        long now = System.currentTimeMillis();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        log.info("营销模板已创建 id={} name={} linkMode={}",
                entity.getId(), entity.getTemplateName(), entity.getLinkMode());
        return converter.toVO(mapper.selectById(entity.getId()));
    }

    /**
     * 更新当前租户指定营销模板。
     *
     * <p>先确认模板存在，再按创建规则校验并锁定待绑定素材。模板 ID 来自路径参数，转换后显式
     * 写回实体；更新完成后按主键回查，保证响应包含最新审计时间。</p>
     *
     * @param id 营销模板 ID
     * @param dto 新的营销模板配置
     * @return 更新后的营销模板
     * @throws BusinessException 模板不存在、参数不合法、名称冲突或绑定素材不可用时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingTemplateVO update(Long id, MarketingTemplateDTO dto) {
        requireExisting(id);
        LinkMode mode = validate(dto, id);
        MarketingTemplate entity = converter.toEntity(dto);
        normalizeByMode(entity, mode);
        lockImage(entity.getImageFileId());
        entity.setId(id);
        entity.setUpdatedAt(System.currentTimeMillis());
        mapper.updateById(entity);
        log.info("营销模板已更新 id={} name={}", id, entity.getTemplateName());
        return converter.toVO(mapper.selectById(id));
    }

    /**
     * 复制当前租户指定营销模板。
     *
     * <p>只逐字段复制模板业务配置，不复制 ID、租户和审计归属；名称追加
     * 「{@value #CLONE_SUFFIX}」并先查重。复制模板绑定图片时，在写入前锁定并校验同一素材。</p>
     *
     * @param id 源营销模板 ID
     * @return 复制生成的新营销模板
     * @throws BusinessException 源模板不存在、副本名称冲突或绑定素材不可用时抛出
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
        lockImage(copy.getImageFileId());
        copy.setRemark(origin.getRemark());
        long now = System.currentTimeMillis();
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        mapper.insert(copy);
        log.info("营销模板已复制 sourceId={} newId={} name={}", id, copy.getId(), cloneName);
        return converter.toVO(mapper.selectById(copy.getId()));
    }

    /**
     * 批量软删除当前租户的营销模板。
     *
     * <p>同一事务内先按固定顺序锁定未删除模板；存在活动拉群营销任务时拒绝删除，否则将仍占用
     * 账号的关联任务按异常终止置为已完成、释放账号并软删除模板。已有终态任务保留历史状态；
     * 空列表直接返回且不访问数据库。</p>
     *
     * @param ids 待删除营销模板 ID 列表
     * @throws BusinessException 租户上下文缺失，或模板仍被活动拉群营销任务引用时抛出
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

    private void lockImage(Long imageFileId) {
        if (imageFileId != null) {
            fileService.lockAndValidateBindableAssets(List.of(imageFileId));
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
