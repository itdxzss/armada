package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.model.vo.MarketingComposedMessageVO;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.marketing.service.MarketingMessageCompositionService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import com.armada.shared.tenant.TenantContext;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 当前租户营销模板读取与统一消息组合实现。 */
@Service
public class MarketingMessageCompositionServiceImpl implements MarketingMessageCompositionService {

    private final MarketingTemplateMapper templateMapper;
    private final MarketingTemplateFileMapper fileMapper;
    private final MarketingMessageComposer messageComposer;

    /**
     * 创建模板消息组合服务。
     *
     * @param templateMapper  模板数据访问
     * @param fileMapper      模板图片数据访问
     * @param messageComposer 营销域统一消息组合器
     */
    public MarketingMessageCompositionServiceImpl(
            MarketingTemplateMapper templateMapper,
            MarketingTemplateFileMapper fileMapper,
            MarketingMessageComposer messageComposer) {
        this.templateMapper = templateMapper;
        this.fileMapper = fileMapper;
        this.messageComposer = messageComposer;
    }

    /** {@inheritDoc} */
    @Override
    public MarketingComposedMessageVO compose(Long marketingTemplateId) {
        if (marketingTemplateId == null || marketingTemplateId < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销模板 ID 必须大于 0");
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        DataScope scope = DataScopeAccess.requireCurrent();
        MarketingTemplate template = templateMapper.selectByIdForScope(marketingTemplateId, scope);
        if (template == null || !Objects.equals(tenantId, template.getTenantId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "营销模板不存在: " + marketingTemplateId);
        }
        MarketingTemplateFile imageFile = template.getImageFileId() == null
                ? null
                : fileMapper.selectByIdForScope(template.getImageFileId(), scope);
        if (template.getImageFileId() != null && imageFile == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND, "营销模板图片不存在: " + template.getImageFileId());
        }
        if (imageFile != null) {
            DataScopeAccess.requireSameOwner(
                    Arrays.asList(template.getOwnerUserId(), imageFile.getOwnerUserId()),
                    "营销模板与图片");
        }
        return toVO(messageComposer.compose(template, imageFile));
    }

    private static MarketingComposedMessageVO toVO(MarketingMessageComposer.ComposedMessage source) {
        return new MarketingComposedMessageVO(
                source.messageType(),
                source.text(),
                source.imageBytes(),
                source.imageMimetype(),
                linkCard(source.linkCard()),
                buttonCard(source.buttonCard()),
                source.mentionAll());
    }

    private static MarketingComposedMessageVO.LinkCardVO linkCard(
            MarketingMessageComposer.LinkCardPayload source) {
        if (source == null) {
            return null;
        }
        return new MarketingComposedMessageVO.LinkCardVO(
                source.url(), source.title(), source.description(), media(source.thumbnail()));
    }

    private static MarketingComposedMessageVO.ButtonCardVO buttonCard(
            MarketingMessageComposer.ButtonCardPayload source) {
        if (source == null) {
            return null;
        }
        return new MarketingComposedMessageVO.ButtonCardVO(
                source.title(),
                source.footer(),
                source.buttons().stream()
                        .map(button -> new MarketingComposedMessageVO.ButtonVO(
                                button.type(), button.displayText(), button.value()))
                        .toList(),
                media(source.thumbnail()));
    }

    private static MarketingComposedMessageVO.MediaVO media(MarketingMessageComposer.MediaPayload source) {
        if (source == null) {
            return null;
        }
        return new MarketingComposedMessageVO.MediaVO(source.bytes(), source.mimetype());
    }
}
