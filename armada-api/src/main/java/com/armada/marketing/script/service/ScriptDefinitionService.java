package com.armada.marketing.script.service;

import com.armada.marketing.converter.ScriptMarketingConverter;
import com.armada.marketing.mapper.ScriptMarketingDefinitionMapper;
import com.armada.marketing.model.dto.ScriptDefinitionSaveDTO;
import com.armada.marketing.model.dto.ScriptMarketingQuery;
import com.armada.marketing.model.entity.ScriptMarketingDefinition;
import com.armada.marketing.model.vo.ScriptDefinitionVO;
import com.armada.marketing.model.vo.ScriptDefinitionSummaryVO;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 养群剧本库；编排素材、角色和间隔，选入任务后不再联动修改任务快照。 */
@Service
public class ScriptDefinitionService {
    private final ScriptMarketingDefinitionMapper mapper;
    private final ScriptMarketingContentService content;
    private final MarketingTemplateFileService assets;
    private final ScriptMarketingConverter converter;
    /** 复用剧本内容校验和公共文件引用锁。 */
    public ScriptDefinitionService(ScriptMarketingDefinitionMapper mapper, ScriptMarketingContentService content,
            MarketingTemplateFileService assets, ScriptMarketingConverter converter) {
        this.mapper = mapper; this.content = content; this.assets = assets; this.converter = converter;
    }
    /** 当前创建人的定义摘要，筛选和分页下推 SQL。 */
    public PageResult<ScriptDefinitionSummaryVO> list(ScriptMarketingQuery query, Long owner) {
        return PageResult.of(mapper.page(query, owner), query.getPage(), query.getPageSize(), mapper.count(query, owner));
    }
    /** 单次读取完整编排，避免列表批量返回大段消息。 */
    public ScriptDefinitionVO detail(Long id, Long owner) {
        var row = requireOwned(mapper.find(id), owner);
        return new ScriptDefinitionVO(row.getId(), row.getName(), row.getEnabled(), row.getUpdatedAt(), content.decode(row.getStepsJson()));
    }
    /** 保存新的可复用剧本，不执行发送。 */
    @Transactional(rollbackFor = Exception.class)
    public ScriptDefinitionVO create(ScriptDefinitionSaveDTO dto, Long owner) {
        var row = prepare(dto); row.setTenantId(TenantContext.get()); row.setCreatedBy(owner);
        row.setCreatedAt(row.getUpdatedAt()); mapper.insert(row); return detail(row.getId(), owner);
    }
    /** 改动仅影响之后选用的任务；旧任务读取自己的完整快照。 */
    @Transactional(rollbackFor = Exception.class)
    public ScriptDefinitionVO update(Long id, ScriptDefinitionSaveDTO dto, Long owner) {
        requireOwned(mapper.lock(id), owner);
        var row = prepare(dto); row.setId(id); mapper.update(row); return detail(id, owner);
    }
    /** 软删定义，任务及发送记录不受影响。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long owner) {
        requireOwned(mapper.lock(id), owner); mapper.delete(id, System.currentTimeMillis());
    }
    private ScriptMarketingDefinition prepare(ScriptDefinitionSaveDTO dto) {
        if (dto == null || dto.name() == null || dto.name().isBlank() || dto.name().length() > 100 || dto.enabled() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "请填写 1–100 字剧本名称和启用状态");
        }
        content.validateRoles(dto.steps());
        if (dto.steps().stream().anyMatch(step -> step.accountId() != null)) {
            throw new BusinessException(ErrorCode.VALIDATION, "剧本库不绑定具体账号，请在创建任务时选择管理员");
        }
        assets.lockAndValidateBindableAssets(dto.steps().stream().map(step -> step.message().imageFileId()).toList());
        var row = converter.toDefinition(dto); row.setName(dto.name().trim());
        row.setStepsJson(content.encode(dto.steps())); row.setUpdatedAt(System.currentTimeMillis()); return row;
    }
    private ScriptMarketingDefinition requireOwned(ScriptMarketingDefinition row, Long owner) {
        if (row == null || !Objects.equals(row.getCreatedBy(), owner)) throw new BusinessException(ErrorCode.NOT_FOUND, "剧本不存在或无权访问");
        return row;
    }
}
