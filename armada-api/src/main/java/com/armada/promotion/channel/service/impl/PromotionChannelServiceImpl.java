package com.armada.promotion.channel.service.impl;

import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.promotion.channel.converter.PromotionChannelConverter;
import com.armada.promotion.channel.mapper.PromotionChannelMapper;
import com.armada.promotion.channel.model.dto.PromotionChannelCreateDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.entity.PromotionChannel;
import com.armada.promotion.channel.model.entity.PromotionChannelTrackingConfig;
import com.armada.promotion.channel.model.entity.PromotionDomain;
import com.armada.promotion.channel.model.entity.PromotionLandingTemplate;
import com.armada.promotion.channel.model.enums.PromotionPlatform;
import com.armada.promotion.channel.model.vo.PromotionChannelVO;
import com.armada.promotion.channel.model.vo.PromotionChannelVoRow;
import com.armada.promotion.channel.security.PromotionTokenCipher;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.promotion.channel.support.ChannelCodeGenerator;
import com.armada.promotion.channel.support.PromotionDomainNormalizer;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 渠道新增与分页实现。租户隔离由现有 MyBatis 拦截器透明处理。 */
@Service
public class PromotionChannelServiceImpl implements PromotionChannelService {

    private static final Logger log = LoggerFactory.getLogger(PromotionChannelServiceImpl.class);
    private static final int CHANNEL_CODE_RETRY_LIMIT = 5;
    private static final int OWNER_FILTER_MAX = 500;
    private static final String DEFAULT_LEAD_EVENT = "Lead";
    private static final String DEFAULT_LOGIN_REQUEST_EVENT = "InitiateCheckout";
    private static final String DEFAULT_LOGIN_SUCCESS_EVENT = "CompleteRegistration";

    private final PromotionChannelMapper mapper;
    private final CountryService countryService;
    private final PromotionChannelConverter converter;
    private final ChannelCodeGenerator codeGenerator;
    private final PromotionTokenCipher tokenCipher;

    public PromotionChannelServiceImpl(
            PromotionChannelMapper mapper,
            CountryService countryService,
            PromotionChannelConverter converter,
            ChannelCodeGenerator codeGenerator,
            PromotionTokenCipher tokenCipher) {
        this.mapper = mapper;
        this.countryService = countryService;
        this.converter = converter;
        this.codeGenerator = codeGenerator;
        this.tokenCipher = tokenCipher;
    }

    /**
     * {@inheritDoc}
     *
     * <p>域名、渠道和追踪配置处于同一事务，任何一步失败都会整体回滚。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromotionChannelVO create(PromotionChannelCreateDTO request) {
        // 步骤1：统一完成必填、长度、平台能力和域名格式校验，避免无效数据进入后续数据库操作。
        ValidatedCreate value = validate(request);

        // 步骤2：模板、目标国家和预选区号必须引用现有主数据；跨业务域的国家数据只通过 CountryService 获取。
        PromotionLandingTemplate template = mapper.selectAvailableTemplateById(value.landingTemplateId());
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "绑定模板不存在或已停用: " + value.landingTemplateId());
        }
        CountryReferenceVO targetCountry = value.targetCountryId() == null
                ? null : countryService.requireActiveReference(value.targetCountryId());
        CountryReferenceVO preselectedCountry =
                countryService.requireActiveReference(value.preselectedCountryId());

        // 步骤3：同域名同模板直接复用；同域名跨模板立即拒绝，数据库唯一键负责兜住并发竞争。
        PromotionDomain domain = resolveDomain(value);
        long now = System.currentTimeMillis();

        // 步骤4：生成不可预测的公开渠道码并插入主记录；极低概率碰撞时由唯一键触发重试。
        PromotionChannel channel = buildChannel(value, domain.getId(), now);
        insertChannelWithCodeRetry(channel);

        // 步骤5：只有支持 CAPI 的平台才创建追踪配置；Token 在进入 Mapper 前已经完成应用层加密。
        if (value.platform().capiSupported()) {
            mapper.insertTrackingConfig(buildTrackingConfig(value, channel.getId(), now));
        }

        // 步骤6：直接使用本事务已经确认的数据组装响应，响应模型从结构上排除 Token 等敏感字段。
        PromotionChannelVoRow saved = toRow(channel, domain, template, value.trackingId());
        log.info("推广渠道已创建 id={} code={} ownerUserId={}",
                channel.getId(), channel.getChannelCode(), channel.getOwnerUserId());
        return converter.toVO(saved, targetCountry, preselectedCountry);
    }

    /**
     * {@inheritDoc}
     *
     * <p>count 与 select 复用 Mapper 筛选条件，国家展示信息按本页 ID 一次批量补齐。</p>
     */
    @Override
    public PageResult<PromotionChannelVO> page(PromotionChannelQuery query) {
        // 步骤1：限制筛选值和 IN 集合规模，避免无效 ID 或超大查询拖垮数据库。
        validateQuery(query);

        // 步骤2：先查总数；无数据时不再执行列表 SQL 和国家主数据查询。
        long total = mapper.countPage(query);
        if (total == 0) {
            return PageResult.of(List.of(), query.getPage(), query.getPageSize(), 0);
        }

        // 步骤3：分页、筛选和排序全部下推 MySQL，禁止在内存中对全量渠道裁剪。
        List<PromotionChannelVoRow> rows = mapper.selectPage(query);

        // 步骤4：收集本页国家 ID 后一次批量读取展示信息，避免逐行查询产生 N+1。
        Set<Long> countryIds = new LinkedHashSet<>();
        for (PromotionChannelVoRow row : rows) {
            if (row.getTargetCountryId() != null) {
                countryIds.add(row.getTargetCountryId());
            }
            if (row.getPreselectedCountryId() != null) {
                countryIds.add(row.getPreselectedCountryId());
            }
        }
        Map<Long, CountryReferenceVO> countries =
                countryService.referencesByIds(new ArrayList<>(countryIds));

        // 步骤5：补齐国家名称、区号、平台和链接等页面展示字段；Token 始终不进入分页投影。
        List<PromotionChannelVO> items = rows.stream()
                .map(row -> converter.toVO(
                        row,
                        countries.get(row.getTargetCountryId()),
                        countries.get(row.getPreselectedCountryId())))
                .toList();
        return PageResult.of(items, query.getPage(), query.getPageSize(), total);
    }

    /**
     * 校验并规范化新增参数。
     *
     * <p>FB/TikTok 的追踪 ID 与 Token 必须成对出现；快手和 MGSKY Ads 不接受 CAPI 字段。</p>
     */
    private ValidatedCreate validate(PromotionChannelCreateDTO request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新增渠道参数不能为空");
        }
        String channelName = requiredText(request.channelName(), "渠道名称", 128);
        requirePositive(request.ownerUserId(), "归属用户");
        requirePositive(request.landingTemplateId(), "绑定模板");
        requirePositive(request.preselectedCountryId(), "预选区号");
        if (request.targetCountryId() != null) {
            requirePositive(request.targetCountryId(), "目标国家");
        }
        PromotionPlatform platform = PromotionPlatform.require(request.platform());
        String domainHost = PromotionDomainNormalizer.normalize(request.domain());
        String trackingId = optionalText(request.trackingId(), "Pixel/追踪 ID", 128);
        String token = optionalTextByBytes(request.accessToken(), "Access Token", 3000);
        boolean hasTrackingId = StringUtils.hasText(trackingId);
        boolean hasToken = StringUtils.hasText(token);
        if (!platform.capiSupported() && (hasTrackingId || hasToken
                || StringUtils.hasText(request.leadEventName())
                || StringUtils.hasText(request.loginRequestEventName())
                || StringUtils.hasText(request.loginSuccessEventName()))) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    platform.label() + " 当前不支持 CAPI 追踪配置");
        }
        if (platform.capiSupported() && hasTrackingId != hasToken) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "Pixel/追踪 ID 与 Access Token 必须同时填写或同时留空");
        }
        return new ValidatedCreate(
                channelName,
                request.ownerUserId(),
                request.targetCountryId(),
                request.landingTemplateId(),
                domainHost,
                request.preselectedCountryId(),
                platform,
                trackingId,
                token,
                eventName(request.leadEventName(), DEFAULT_LEAD_EVENT),
                eventName(request.loginRequestEventName(), DEFAULT_LOGIN_REQUEST_EVENT),
                eventName(request.loginSuccessEventName(), DEFAULT_LOGIN_SUCCESS_EVENT),
                request.inAppOpenAllowed() == null || request.inAppOpenAllowed(),
                request.marketingAllowed() == null || request.marketingAllowed());
    }

    /**
     * 获取或创建域名绑定。
     *
     * <p>先查后插用于正常路径，唯一键用于解决两个请求同时创建同一域名的竞态。</p>
     */
    private PromotionDomain resolveDomain(ValidatedCreate value) {
        PromotionDomain existing = mapper.selectActiveDomainByHost(value.domainHost());
        if (existing != null) {
            requireSameTemplate(existing, value.landingTemplateId());
            return existing;
        }
        long now = System.currentTimeMillis();
        PromotionDomain created = new PromotionDomain();
        created.setDomainHost(value.domainHost());
        created.setLandingTemplateId(value.landingTemplateId());
        created.setCreatedBy(value.ownerUserId());
        created.setUpdatedBy(value.ownerUserId());
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            mapper.insertDomain(created);
            return created;
        } catch (DuplicateKeyException ex) {
            // 另一个并发事务先完成插入后重新读取；若当前租户仍不可见，说明域名已被其他范围占用。
            PromotionDomain concurrent = mapper.selectActiveDomainByHost(value.domainHost());
            if (concurrent == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "访问域名已被占用: " + value.domainHost());
            }
            requireSameTemplate(concurrent, value.landingTemplateId());
            return concurrent;
        }
    }

    /** 确保已有域名没有绑定到其他模板。 */
    private static void requireSameTemplate(PromotionDomain domain, Long templateId) {
        if (!templateId.equals(domain.getLandingTemplateId())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "访问域名已绑定其他模板，请更换域名: " + domain.getDomainHost());
        }
    }

    /** 根据已校验参数构造渠道写入实体；创建人按当前需求取归属用户。 */
    private PromotionChannel buildChannel(ValidatedCreate value, Long domainId, long now) {
        PromotionChannel row = new PromotionChannel();
        row.setChannelName(value.channelName());
        row.setOwnerUserId(value.ownerUserId());
        row.setPromotionDomainId(domainId);
        row.setTargetCountryId(value.targetCountryId());
        row.setPreselectedCountryId(value.preselectedCountryId());
        row.setPlatform(value.platform().code());
        row.setIsInAppOpenAllowed(value.inAppOpenAllowed() ? 1 : 0);
        row.setIsMarketingAllowed(value.marketingAllowed() ? 1 : 0);
        row.setStatus(1);
        row.setCreatedBy(value.ownerUserId());
        row.setUpdatedBy(value.ownerUserId());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * 插入渠道并处理渠道码碰撞。
     *
     * <p>不采用“先查渠道码是否存在”，因为先查后插仍然存在并发窗口，唯一键才是最终一致性保障。</p>
     */
    private void insertChannelWithCodeRetry(PromotionChannel channel) {
        for (int attempt = 1; attempt <= CHANNEL_CODE_RETRY_LIMIT; attempt++) {
            channel.setId(null);
            channel.setChannelCode(codeGenerator.generate());
            try {
                mapper.insertChannel(channel);
                return;
            } catch (DuplicateKeyException ex) {
                if (attempt == CHANNEL_CODE_RETRY_LIMIT) {
                    throw new BusinessException(ErrorCode.CONFLICT, "渠道推广码生成冲突，请重试");
                }
            }
        }
    }

    /** 构造追踪配置，并在应用层把 Access Token 转成密文、密钥版本和不可逆指纹。 */
    private PromotionChannelTrackingConfig buildTrackingConfig(
            ValidatedCreate value,
            Long channelId,
            long now) {
        PromotionChannelTrackingConfig row = new PromotionChannelTrackingConfig();
        row.setChannelId(channelId);
        row.setProviderType(value.platform().code());
        row.setTrackingId(value.trackingId());
        if (StringUtils.hasText(value.accessToken())) {
            PromotionTokenCipher.EncryptedToken encrypted = tokenCipher.encrypt(value.accessToken());
            row.setAccessTokenCiphertext(encrypted.ciphertext());
            row.setEncryptionKeyId(encrypted.keyId());
            row.setTokenFingerprint(encrypted.fingerprint());
        }
        row.setLeadEventName(value.leadEventName());
        row.setLoginRequestEventName(value.loginRequestEventName());
        row.setLoginSuccessEventName(value.loginSuccessEventName());
        row.setCreatedBy(value.ownerUserId());
        row.setUpdatedBy(value.ownerUserId());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 把刚创建的多表数据整理成与分页 Mapper 一致的投影，再统一交给转换器生成 VO。 */
    private static PromotionChannelVoRow toRow(
            PromotionChannel channel,
            PromotionDomain domain,
            PromotionLandingTemplate template,
            String trackingId) {
        PromotionChannelVoRow row = new PromotionChannelVoRow();
        row.setId(channel.getId());
        row.setChannelName(channel.getChannelName());
        row.setChannelCode(channel.getChannelCode());
        row.setOwnerUserId(channel.getOwnerUserId());
        row.setTargetCountryId(channel.getTargetCountryId());
        row.setPreselectedCountryId(channel.getPreselectedCountryId());
        row.setLandingTemplateId(template.getId());
        row.setTemplateName(template.getTemplateName());
        row.setDomainHost(domain.getDomainHost());
        row.setPlatform(channel.getPlatform());
        row.setTrackingId(trackingId);
        row.setStatus(channel.getStatus());
        row.setIsInAppOpenAllowed(channel.getIsInAppOpenAllowed());
        row.setIsMarketingAllowed(channel.getIsMarketingAllowed());
        row.setCreatedAt(channel.getCreatedAt());
        return row;
    }

    /** 校验分页筛选条件，并对上级用户展开集合去重、限流。 */
    private static void validateQuery(PromotionChannelQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "分页查询参数不能为空");
        }
        if (query.getTargetCountryId() != null) {
            requirePositive(query.getTargetCountryId(), "目标国家");
        }
        if (query.getLandingTemplateId() != null) {
            requirePositive(query.getLandingTemplateId(), "绑定模板");
        }
        if (query.getCreatorUserId() != null) {
            requirePositive(query.getCreatorUserId(), "创建人");
        }
        List<Long> ownerUserIds = query.getOwnerUserIds();
        if (ownerUserIds == null) {
            return;
        }
        if (ownerUserIds.size() > OWNER_FILTER_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "上级用户筛选展开的归属用户不能超过 " + OWNER_FILTER_MAX + " 个");
        }
        if (ownerUserIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION, "归属用户ID必须为正整数");
        }
        query.setOwnerUserIds(ownerUserIds.stream().distinct().toList());
    }

    /** 校验必填文本并返回去除首尾空白后的值。 */
    private static String requiredText(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION, field + "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, field + "长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    /** 校验可选文本；空白统一转成 null，避免数据库出现无意义空串。 */
    private static String optionalText(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return requiredText(value, field, maxLength);
    }

    /** 按 UTF-8 字节数限制 Token，确保 AES-GCM 密文不会超过数据库列宽。 */
    private static String optionalTextByBytes(String value, String field, int maxBytes) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new BusinessException(ErrorCode.VALIDATION, field + "长度过长");
        }
        return trimmed;
    }

    /** 上报事件未填写时使用产品默认事件名，填写时执行统一长度校验。 */
    private static String eventName(String value, String defaultValue) {
        return StringUtils.hasText(value) ? requiredText(value, "上报事件", 64) : defaultValue;
    }

    /** 校验所有业务主键均为正整数。 */
    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, field + "ID必须为正整数");
        }
    }

    private record ValidatedCreate(
            String channelName,
            Long ownerUserId,
            Long targetCountryId,
            Long landingTemplateId,
            String domainHost,
            Long preselectedCountryId,
            PromotionPlatform platform,
            String trackingId,
            String accessToken,
            String leadEventName,
            String loginRequestEventName,
            String loginSuccessEventName,
            boolean inAppOpenAllowed,
            boolean marketingAllowed) {
    }
}
