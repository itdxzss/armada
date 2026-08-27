package com.armada.promotion.channel.service.impl;

import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.promotion.channel.converter.PromotionChannelConverter;
import com.armada.promotion.channel.mapper.PromotionChannelMapper;
import com.armada.promotion.channel.model.dto.PromotionChannelCreateDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelCapiEventDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.dto.PromotionChannelProbeDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelUpdateDTO;
import com.armada.promotion.channel.model.entity.PromotionChannel;
import com.armada.promotion.channel.model.entity.PromotionChannelTrackingConfig;
import com.armada.promotion.channel.model.entity.PromotionDomain;
import com.armada.promotion.channel.model.entity.PromotionLandingTemplate;
import com.armada.promotion.channel.model.enums.FacebookStandardEvent;
import com.armada.promotion.channel.model.enums.PromotionPlatform;
import com.armada.promotion.channel.model.vo.FacebookStandardEventVO;
import com.armada.promotion.channel.model.vo.PromotionChannelDetailRow;
import com.armada.promotion.channel.model.vo.PromotionChannelDetailVO;
import com.armada.promotion.channel.model.vo.PromotionChannelCapiDeliveryResult;
import com.armada.promotion.channel.model.vo.PromotionChannelProbeConfigRow;
import com.armada.promotion.channel.model.vo.PromotionChannelProbeVO;
import com.armada.promotion.channel.model.vo.PromotionChannelPairingContextRow;
import com.armada.promotion.channel.model.vo.PromotionChannelRuntimeRow;
import com.armada.promotion.channel.model.vo.PromotionChannelRuntimeVO;
import com.armada.promotion.channel.model.vo.PromotionChannelVO;
import com.armada.promotion.channel.model.vo.PromotionChannelVoRow;
import com.armada.promotion.channel.security.PromotionTokenCipher;
import com.armada.promotion.channel.service.FacebookCapiClient;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.promotion.channel.support.ChannelCodeGenerator;
import com.armada.promotion.channel.support.PromotionChannelLinkBuilder;
import com.armada.promotion.channel.support.PromotionDomainNormalizer;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import com.armada.shared.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 渠道新增、分页、编辑与删除实现。租户隔离由现有 MyBatis 拦截器透明处理。 */
@Service
public class PromotionChannelServiceImpl implements PromotionChannelService {

    private static final Logger log = LoggerFactory.getLogger(PromotionChannelServiceImpl.class);
    private static final int CHANNEL_CODE_RETRY_LIMIT = 5;
    private static final int OWNER_FILTER_MAX = 500;
    private static final int CHANNEL_STATUS_DISABLED = 0;
    private static final int CHANNEL_STATUS_ENABLED = 1;
    private static final String DEFAULT_LEAD_EVENT = FacebookStandardEvent.LEAD.code();
    private static final String DEFAULT_LOGIN_REQUEST_EVENT = FacebookStandardEvent.INITIATE_CHECKOUT.code();
    private static final String DEFAULT_LOGIN_SUCCESS_EVENT = FacebookStandardEvent.COMPLETE_REGISTRATION.code();
    private static final String DEFAULT_THEME_COLOR = "#e11d48";
    private static final Pattern THEME_COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern CHANNEL_CODE_PATTERN = Pattern.compile("^[a-z0-9]{1,32}$");
    private static final String PROBE_EVENT_NAME = "PageView";
    private static final String PROBE_STATUS_NORMAL = "NORMAL";
    private static final String PROBE_STATUS_ABNORMAL = "ABNORMAL";
    private static final String PROBE_ERROR_UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM";
    private static final String PROBE_ERROR_UNCONFIGURED = "UNCONFIGURED";
    private static final String PROBE_ERROR_TOKEN_DECRYPT_FAILED = "TOKEN_DECRYPT_FAILED";
    private static final String PROBE_ERROR_INVALID_RESPONSE = "INVALID_PLATFORM_RESPONSE";
    private static final String PROBE_ERROR_CONFIG_CHANGED = "CONFIG_CHANGED";
    private static final int PROBE_DB_STATUS_RUNNING = 0;
    private static final int PROBE_DB_STATUS_SUCCESS = 1;
    private static final int PROBE_DB_STATUS_FAILED = 2;
    private static final long PROBE_LOCK_TIMEOUT_MILLIS = 60_000L;
    private static final long PROBE_COOLDOWN_MILLIS = 30_000L;
    private static final int PROBE_ERROR_MESSAGE_MAX_LENGTH = 255;
    private static final Pattern TEST_EVENT_CODE_PATTERN =
            Pattern.compile("^TEST[A-Za-z0-9_-]{1,60}$");
    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final PromotionChannelMapper mapper;
    private final CountryService countryService;
    private final PromotionChannelConverter converter;
    private final ChannelCodeGenerator codeGenerator;
    private final PromotionTokenCipher tokenCipher;
    private final FacebookCapiClient facebookCapiClient;
    private final boolean probeEnabled;

    public PromotionChannelServiceImpl(
            PromotionChannelMapper mapper,
            CountryService countryService,
            PromotionChannelConverter converter,
            ChannelCodeGenerator codeGenerator,
            PromotionTokenCipher tokenCipher,
            FacebookCapiClient facebookCapiClient,
            @Value("${armada.promotion.tracking.facebook.probe-enabled:false}") boolean probeEnabled) {
        this.mapper = mapper;
        this.countryService = countryService;
        this.converter = converter;
        this.codeGenerator = codeGenerator;
        this.tokenCipher = tokenCipher;
        this.facebookCapiClient = facebookCapiClient;
        this.probeEnabled = probeEnabled;
    }

    @Override
    public List<FacebookStandardEventVO> facebookStandardEvents() {
        return java.util.Arrays.stream(FacebookStandardEvent.values())
                .map(event -> new FacebookStandardEventVO(
                        event.code(), event.nameZh(), event.nameEn()))
                .toList();
    }

    @Override
    public PromotionChannelCapiDeliveryResult deliverFacebookCapi(
            PromotionChannelCapiEventDTO event) {
        if (event == null || event.channelId() == null || event.channelId() <= 0
                || !FacebookStandardEvent.supports(event.eventName())
                || !StringUtils.hasText(event.eventId())
                || event.eventTimeSeconds() == null || event.eventTimeSeconds() <= 0
                || !StringUtils.hasText(event.phoneSha256())
                || !SHA256_HEX_PATTERN.matcher(event.phoneSha256()).matches()) {
            return deliveryFailure(false, "INVALID_EVENT", "正式事件参数不完整或不合法");
        }
        DataScope scope = DataScopeAccess.requireCurrent();
        PromotionChannelProbeConfigRow config =
                mapper.selectProbeConfigByChannelIdForScope(event.channelId(), scope);
        if (config == null || config.getPlatform() == null
                || config.getPlatform() != PromotionPlatform.FACEBOOK.code()
                || !StringUtils.hasText(config.getTrackingId()) || !hasCompleteToken(config)) {
            return deliveryFailure(false, "UNCONFIGURED", "Facebook Pixel ID 或 Access Token 未配置");
        }
        final String accessToken;
        try {
            accessToken = tokenCipher.decrypt(
                    config.getAccessTokenCiphertext(),
                    config.getEncryptionKeyId(),
                    config.getTokenFingerprint());
        } catch (RuntimeException ex) {
            return deliveryFailure(false, "TOKEN_DECRYPT_FAILED", "Access Token 解密失败");
        }
        FacebookCapiClient.Result result = facebookCapiClient.send(
                new FacebookCapiClient.BusinessEventCommand(
                        config.getTrackingId(), accessToken, event.eventSourceUrl(),
                        event.eventName(), event.eventId(), event.eventTimeSeconds(),
                        event.phoneSha256(), event.clientIp(), event.clientUserAgent(),
                        event.fbp(), event.fbc()));
        return new PromotionChannelCapiDeliveryResult(
                result.success(), result.retryable(), result.errorCode(), result.errorMessage());
    }

    private static PromotionChannelCapiDeliveryResult deliveryFailure(
            boolean retryable, String errorCode, String errorMessage) {
        return new PromotionChannelCapiDeliveryResult(false, retryable, errorCode, errorMessage);
    }

    /**
     * {@inheritDoc}
     *
     * <p>域名、渠道和追踪配置处于同一事务，任何一步失败都会整体回滚。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromotionChannelVO create(PromotionChannelCreateDTO request) {
        DataScope scope = DataScopeAccess.requireCurrent();
        // 步骤1：统一完成必填、长度、平台能力和域名格式校验，避免无效数据进入后续数据库操作。
        ValidatedWrite value = validate(request, scope.ownerUserIdForCreate());

        // 步骤2：模板、目标国家和预选区号必须引用现有主数据；跨业务域的国家数据只通过 CountryService 获取。
        PromotionLandingTemplate template = mapper.selectAvailableTemplateById(value.landingTemplateId());
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "绑定模板不存在或已停用: " + value.landingTemplateId());
        }
        CountryOptionVO targetCountry = countryService.requireActiveOption(value.targetCountry(), true);
        CountryOptionVO preselectedCountry =
                countryService.requireActiveOption(value.preselectedCountry(), false);

        // 步骤3：同模板同域名直接复用；域名或模板任一侧冲突都拒绝，唯一键负责兜住并发竞争。
        PromotionDomain domain = resolveDomain(value);
        long now = System.currentTimeMillis();

        // 步骤4：生成不可预测的公开渠道码并插入主记录；极低概率碰撞时由唯一键触发重试。
        PromotionChannel channel = buildChannel(
                value, targetCountry.value(), preselectedCountry.value(), domain.getId(), now);
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
        DataScope scope = DataScopeAccess.requireCurrent();
        // 步骤1：限制筛选值和 IN 集合规模，避免无效 ID 或超大查询拖垮数据库。
        validateQuery(query);
        query.applyDataScope(scope);

        // 步骤2：先查总数；无数据时不再执行列表 SQL 和国家主数据查询。
        long total = mapper.countPage(query);
        if (total == 0) {
            return PageResult.of(List.of(), query.getPage(), query.getPageSize(), 0);
        }

        // 步骤3：分页、筛选和排序全部下推 MySQL，禁止在内存中对全量渠道裁剪。
        List<PromotionChannelVoRow> rows = mapper.selectPage(query);

        // 步骤4：收集本页国家 ID 后一次批量读取展示信息，避免逐行查询产生 N+1。
        Set<String> countryValues = new LinkedHashSet<>();
        for (PromotionChannelVoRow row : rows) {
            if (row.getTargetCountry() != null) {
                countryValues.add(row.getTargetCountry());
            }
            if (row.getPreselectedCountry() != null) {
                countryValues.add(row.getPreselectedCountry());
            }
        }
        Map<String, CountryOptionVO> countries =
                countryService.optionsByValues(List.copyOf(countryValues));

        // 步骤5：补齐国家名称、区号、平台和链接等页面展示字段；Token 始终不进入分页投影。
        List<PromotionChannelVO> items = rows.stream()
                .map(row -> converter.toVO(
                        row,
                        countries.get(row.getTargetCountry()),
                        countries.get(row.getPreselectedCountry())))
                .toList();
        return PageResult.of(items, query.getPage(), query.getPageSize(), total);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PromotionChannelDetailVO detail(Long id) {
        requirePositive(id, "渠道");
        DataScope scope = DataScopeAccess.requireCurrent();

        // 单条 SQL 读取表单所需字段；Mapper 投影从类型上排除 Token 明文、密文和指纹。
        PromotionChannelDetailRow row = mapper.selectDetailById(id, scope);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "渠道不存在或已删除: " + id);
        }
        return converter.toDetailVO(row);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PromotionChannelRuntimeVO runtime(String channelCode, String forwardedHost) {
        // 步骤1：推广码统一转小写并限制为数据库允许的公开短码字符，拒绝路径等异常输入。
        String normalizedCode = requiredText(channelCode, "渠道推广码", 32)
                .toLowerCase(Locale.ROOT);
        if (!CHANNEL_CODE_PATTERN.matcher(normalizedCode).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "渠道推广码格式不正确");
        }

        // 步骤2：复用渠道写入的域名规范化规则，确保 X-Forwarded-Host 与库内唯一域名同口径比较。
        String domainHost = PromotionDomainNormalizer.normalize(forwardedHost);

        // 步骤3：公共请求没有租户上下文，Mapper 通过“推广码+域名+三表租户一致”精确解析且只返回非敏感字段。
        PromotionChannelRuntimeRow row = mapper.selectRuntimeByCodeAndHost(normalizedCode, domainHost);
        if (row == null) {
            // 不区分推广码、域名、状态或模板哪项不匹配，避免公开接口泄露渠道存在性。
            throw new BusinessException(ErrorCode.NOT_FOUND, "渠道不存在、已停用或访问域名不匹配");
        }
        return new PromotionChannelRuntimeVO(
                row.getTemplateCode(),
                row.getThemeColor(),
                Integer.valueOf(1).equals(row.getIsAppDownloadShown()),
                row.getTargetCountry(),
                row.getPreselectedCountry());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public PromotionChannelPairingContextRow resolvePairingContext(String channelCode, String forwardedHost) {
        String normalizedCode = channelCode == null
                ? ""
                : channelCode.trim().toLowerCase(Locale.ROOT);
        if (!CHANNEL_CODE_PATTERN.matcher(normalizedCode).matches()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "推广渠道不存在或已停用");
        }
        String normalizedHost = PromotionDomainNormalizer.normalize(forwardedHost);
        PromotionChannelPairingContextRow context =
                mapper.selectPairingContextByCodeAndHost(normalizedCode, normalizedHost);
        if (context == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "推广渠道不存在或已停用");
        }
        return context;
    }

    /** {@inheritDoc} */
    @Override
    public PromotionChannelProbeVO probe(Long id, PromotionChannelProbeDTO request) {
        DataScope scope = DataScopeAccess.requireCurrent();
        if (!probeEnabled) {
            throw new BusinessException(ErrorCode.VALIDATION, "Facebook CAPI 探测功能未启用");
        }
        requirePositive(id, "渠道");
        PromotionChannelProbeConfigRow config = mapper.selectProbeConfigByChannelIdForScope(id, scope);
        if (config == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "渠道不存在或已删除: " + id);
        }

        long checkedAt = System.currentTimeMillis();
        if (!Integer.valueOf(PromotionPlatform.FACEBOOK.code()).equals(config.getPlatform())) {
            return rejectedProbe(config, PROBE_ERROR_UNSUPPORTED_PLATFORM,
                    "当前推广平台不支持 Facebook CAPI 探测，未发起探测", checkedAt);
        }
        if (!hasCompleteTrackingConfig(config)) {
            return rejectedProbe(config, PROBE_ERROR_UNCONFIGURED,
                    "未配置 Pixel ID 或 Access Token，未发起探测", checkedAt);
        }

        String testEventCode = requireTestEventCode(request);
        PromotionChannelTrackingConfig running = probeUpdate(
                config, PROBE_DB_STATUS_RUNNING, checkedAt, scope.actorUserId());
        running.setLastProbeEventName(PROBE_EVENT_NAME);
        if (mapper.markProbeRunning(
                running,
                checkedAt - PROBE_LOCK_TIMEOUT_MILLIS,
                checkedAt - PROBE_COOLDOWN_MILLIS) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "渠道正在探测或操作过于频繁，请稍后重试");
        }

        final String accessToken;
        try {
            accessToken = tokenCipher.decrypt(
                    config.getAccessTokenCiphertext(),
                    config.getEncryptionKeyId(),
                    config.getTokenFingerprint());
        } catch (BusinessException ex) {
            return completeProbe(config, false, null,
                    PROBE_ERROR_TOKEN_DECRYPT_FAILED,
                    "Access Token 无法解密，请重新配置", checkedAt,
                    System.currentTimeMillis(), scope.actorUserId());
        }

        String eventId = "probe_" + UUID.randomUUID().toString().replace("-", "");
        long eventTimeSeconds = System.currentTimeMillis() / 1000;
        FacebookCapiClient.ProbeCommand command = new FacebookCapiClient.ProbeCommand(
                config.getTrackingId(), accessToken, testEventCode,
                promotionLink(config), PROBE_EVENT_NAME, eventId, eventTimeSeconds,
                sha256Hex(eventId));
        FacebookCapiClient.Result clientResult = facebookCapiClient.probe(command);
        if (clientResult == null) {
            return completeProbe(config, false, eventId, PROBE_ERROR_INVALID_RESPONSE,
                    "Facebook 返回结果无法识别", checkedAt,
                    System.currentTimeMillis(), scope.actorUserId());
        }
        return completeProbe(config, clientResult.success(), eventId,
                clientResult.errorCode(), clientResult.errorMessage(), checkedAt,
                System.currentTimeMillis(), scope.actorUserId());
    }

    /**
     * {@inheritDoc}
     *
     * <p>渠道、域名引用和追踪配置处于同一事务。编辑不会修改公开渠道码和创建信息；
     * 只有平台和追踪 ID 未变且旧密文完整时，Token 留空才保留原值。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, PromotionChannelUpdateDTO request) {
        requirePositive(id, "渠道");
        DataScope scope = DataScopeAccess.requireCurrent();
        ValidatedWrite value = validate(request, scope.actorUserId());

        // 模板与国家继续复用新增路径的主数据校验，防止编辑绕过已有业务约束。
        requireAvailableTemplate(value.landingTemplateId());
        CountryOptionVO targetCountry = countryService.requireActiveOption(value.targetCountry(), true);
        CountryOptionVO preselectedCountry =
                countryService.requireActiveOption(value.preselectedCountry(), false);

        // 先锁定目标域名再锁渠道，和删除流程保持统一锁序，避免写入已被并发释放的域名。
        PromotionDomain domain = resolveDomain(value);
        PromotionChannel existing = requireActiveChannel(id, scope);
        requireReusableTrackingToken(value, existing);
        long now = System.currentTimeMillis();
        PromotionChannel channel = buildChannel(
                value, targetCountry.value(), preselectedCountry.value(), domain.getId(), now);
        channel.setId(id);
        // 归属在共享/转移功能上线前不可通过编辑改变；管理员编辑他人渠道时也保留原 owner。
        channel.setOwnerUserId(existing.getOwnerUserId());
        if (mapper.updateChannel(channel) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "渠道不存在或已删除: " + id);
        }

        syncTrackingConfig(value, existing, now);
        log.info("推广渠道已更新 id={} ownerUserId={} actorUserId={} platform={} status={}",
                id, existing.getOwnerUserId(), scope.actorUserId(), value.platform().code(), value.status());
    }

    /**
     * {@inheritDoc}
     *
     * <p>追踪配置与渠道在同一事务内软删除；域名和账号历史引用不参与级联。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requirePositive(id, "渠道");
        DataScope scope = DataScopeAccess.requireCurrent();
        long actorUserId = scope.actorUserId();
        Long tenantId = TenantContext.get();
        // 先锁共享域名、再锁渠道，保证同一域名下多个渠道并发删除时不会形成交叉等待。
        PromotionDomain domain = mapper.selectActiveDomainByChannelIdForUpdate(id, scope);
        PromotionChannel channel = requireActiveChannel(id, scope);
        if (domain == null || !domain.getId().equals(channel.getPromotionDomainId())) {
            // 等待渠道锁期间若编辑已切换域名，本次旧快照不能继续判断，应回滚后重试。
            throw new BusinessException(ErrorCode.CONFLICT, "渠道域名绑定已变化，请重试删除");
        }
        long now = System.currentTimeMillis();

        // 渠道与追踪配置保留软删历史，不破坏账号等存量数据对渠道的引用。
        if (mapper.softDeleteChannel(id, actorUserId, now) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "渠道不存在或已删除: " + id);
        }
        mapper.softDeleteTrackingConfig(id, actorUserId, now);

        // 只有不存在其他有效渠道时才释放模板—域名关系；共享该关系的其他渠道不会受影响。
        boolean domainReleased = false;
        if (domain != null
                && mapper.selectAnyActiveChannelIdByDomainForUpdate(tenantId, domain.getId()) == null) {
            if (mapper.softDeleteDomain(domain.getId(), actorUserId, now) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "域名绑定状态已变化，请重试");
            }
            domainReleased = true;
        }
        log.info("推广渠道已软删除 id={} ownerUserId={} actorUserId={} domainReleased={}",
                id, channel.getOwnerUserId(), actorUserId, domainReleased);
    }

    /** 平台不支持或配置不完整时返回页面可展示的失败详情，不调用 Facebook。 */
    private PromotionChannelProbeVO rejectedProbe(
            PromotionChannelProbeConfigRow config,
            String errorCode,
            String errorMessage,
            long probedAt) {
        // 缺失配置不能进入“探测中”，因此不抢占也不回写；分页状态继续稳定显示未配置。
        log.info("渠道CAPI探测未发起 id={} result={}", config.getChannelId(), errorCode);
        return new PromotionChannelProbeVO(
                false, PROBE_STATUS_ABNORMAL, config.getTrackingId(),
                hasCompleteToken(config), null, null,
                errorCode, errorMessage, probedAt);
    }

    /** 将 Facebook 调用结论脱敏落库并组装统一探测详情。 */
    private PromotionChannelProbeVO completeProbe(
            PromotionChannelProbeConfigRow config,
            boolean success,
            String eventId,
            String errorCode,
            String errorMessage,
            long startedAt,
            long probedAt,
            long actorUserId) {
        String stableErrorCode = success
                ? null
                : (StringUtils.hasText(errorCode) ? errorCode : PROBE_ERROR_INVALID_RESPONSE);
        String stableErrorMessage = success
                ? null
                : probeErrorMessage(errorMessage);
        String eventName = eventId == null ? null : PROBE_EVENT_NAME;

        PromotionChannelTrackingConfig result = probeUpdate(
                config, success ? PROBE_DB_STATUS_SUCCESS : PROBE_DB_STATUS_FAILED,
                probedAt, actorUserId);
        result.setLastProbeEventName(eventName);
        result.setLastProbeEventId(eventId);
        result.setLastProbeErrorCode(stableErrorCode);
        result.setLastProbeErrorMessage(stableErrorMessage);
        if (mapper.updateProbeResult(result, startedAt) != 1) {
            // 编辑会清空探测状态并替换指纹；CAS 失败说明本次结果已过期，绝不能覆盖新配置。
            log.info("渠道CAPI探测结果已过期 id={}", config.getChannelId());
            return new PromotionChannelProbeVO(
                    false, PROBE_STATUS_ABNORMAL, config.getTrackingId(), true,
                    eventName, eventId, PROBE_ERROR_CONFIG_CHANGED,
                    "渠道配置在探测期间已变化，请重新探测", probedAt);
        }

        log.info("渠道CAPI探测完成 id={} success={} result={}",
                config.getChannelId(), success, success ? PROBE_STATUS_NORMAL : stableErrorCode);
        return new PromotionChannelProbeVO(
                success,
                success ? PROBE_STATUS_NORMAL : PROBE_STATUS_ABNORMAL,
                config.getTrackingId(),
                true,
                eventName,
                eventId,
                stableErrorCode,
                stableErrorMessage,
                probedAt);
    }

    /** 构造只包含探测状态字段的追踪配置更新对象。 */
    private static PromotionChannelTrackingConfig probeUpdate(
            PromotionChannelProbeConfigRow config,
            int status,
            long updatedAt,
            long actorUserId) {
        PromotionChannelTrackingConfig row = new PromotionChannelTrackingConfig();
        row.setChannelId(config.getChannelId());
        row.setProviderType(config.getPlatform());
        row.setTrackingId(config.getTrackingId());
        row.setTokenFingerprint(config.getTokenFingerprint());
        row.setLastProbeStatus(status);
        row.setLastProbedAt(updatedAt);
        row.setUpdatedBy(actorUserId);
        row.setUpdatedAt(updatedAt);
        return row;
    }

    /** Facebook 真探测要求 Pixel ID 和三项 Token 持久化字段全部完整。 */
    private static boolean hasCompleteTrackingConfig(PromotionChannelProbeConfigRow config) {
        return StringUtils.hasText(config.getTrackingId()) && hasCompleteToken(config);
    }

    /** 三项 Token 持久化字段共同判断配置完整，避免使用半写入或旧版本数据。 */
    private static boolean hasCompleteToken(PromotionChannelProbeConfigRow config) {
        return config.getAccessTokenCiphertext() != null
                && config.getAccessTokenCiphertext().length > 0
                && StringUtils.hasText(config.getEncryptionKeyId())
                && config.getTokenFingerprint() != null
                && config.getTokenFingerprint().length > 0;
    }

    /** 只有真正调用 Facebook 前才要求 Test Event Code，失败详情查询不受影响。 */
    private static String requireTestEventCode(PromotionChannelProbeDTO request) {
        String value = request == null ? null : request.testEventCode();
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION, "Meta Test Event Code 不能为空");
        }
        String trimmed = value.trim();
        if (!TEST_EVENT_CODE_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "Meta Test Event Code 必须以 TEST 开头且不超过64个字符");
        }
        return trimmed;
    }

    /** 渠道链接由受控域名和不可预测渠道码组成，不接受请求方传入 URL。 */
    private static String promotionLink(PromotionChannelProbeConfigRow config) {
        return PromotionChannelLinkBuilder.build(
                config.getDomainHost(), config.getChannelCode());
    }

    /** 使用合成事件 ID 生成不可逆 external_id，探测过程不读取任何真实用户 PII。 */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK缺少SHA-256算法", ex);
        }
    }

    /** 平台错误只保留可操作的短摘要，防止原始响应进入数据库和接口。 */
    private static String probeErrorMessage(String value) {
        String message = StringUtils.hasText(value) ? value.trim() : "Facebook CAPI 探测失败";
        return message.length() <= PROBE_ERROR_MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, PROBE_ERROR_MESSAGE_MAX_LENGTH);
    }

    /**
     * 校验并规范化新增参数。
     *
     * <p>FB/TikTok 的追踪 ID 与 Token 必须成对出现；快手和 MGSKY Ads 不接受 CAPI 字段。</p>
     */
    private ValidatedWrite validate(PromotionChannelCreateDTO request, long actorUserId) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新增渠道参数不能为空");
        }
        return validate(request, false, CHANNEL_STATUS_ENABLED, true, actorUserId);
    }

    /**
     * 校验并规范化编辑参数。
     *
     * <p>字段级校验允许 Token 留空；后续结合已锁定的渠道和追踪配置，
     * 仅在平台、追踪 ID 未变且旧密文完整时允许复用。</p>
     */
    private ValidatedWrite validate(PromotionChannelUpdateDTO request, long actorUserId) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "编辑渠道参数不能为空");
        }
        int status = requireChannelStatus(request.status());
        PromotionChannelCreateDTO common = new PromotionChannelCreateDTO(
                request.channelName(),
                request.ownerUserId(),
                request.targetCountry(),
                request.landingTemplateId(),
                request.domain(),
                request.themeColor(),
                request.showAppDownload(),
                request.preselectedCountry(),
                request.platform(),
                request.trackingId(),
                request.accessToken(),
                request.leadEventName(),
                request.loginRequestEventName(),
                request.loginSuccessEventName(),
                request.inAppOpenAllowed(),
                request.marketingAllowed());
        return validate(common, true, status, false, actorUserId);
    }

    /** 复用新增与编辑的字段校验，并按调用场景区分 Token 留空语义。 */
    private ValidatedWrite validate(
            PromotionChannelCreateDTO request,
            boolean existingTokenMayBeKept,
            int status,
            boolean applyRuntimeDefaults,
            long actorUserId) {
        String channelName = requiredText(request.channelName(), "渠道名称", 128);
        requirePositive(request.landingTemplateId(), "绑定模板");
        String targetCountry = requiredText(request.targetCountry(), "目标国家", 16);
        String preselectedCountry = requiredText(request.preselectedCountry(), "预选区号", 16);
        PromotionPlatform platform = PromotionPlatform.require(request.platform());
        String domainHost = PromotionDomainNormalizer.normalize(request.domain());
        String themeColor = normalizeThemeColor(request.themeColor(), applyRuntimeDefaults);
        Boolean showAppDownload = request.showAppDownload() == null
                ? (applyRuntimeDefaults ? Boolean.TRUE : null)
                : request.showAppDownload();
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
        if (platform.capiSupported()
                && ((!existingTokenMayBeKept && hasTrackingId != hasToken)
                || (existingTokenMayBeKept && hasToken && !hasTrackingId))) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    existingTokenMayBeKept
                            ? "填写新 Access Token 时必须同时填写 Pixel/追踪 ID"
                            : "Pixel/追踪 ID 与 Access Token 必须同时填写或同时留空");
        }
        return new ValidatedWrite(
                channelName,
                actorUserId,
                targetCountry,
                request.landingTemplateId(),
                domainHost,
                themeColor,
                showAppDownload,
                preselectedCountry,
                platform,
                trackingId,
                token,
                eventName(platform, request.leadEventName(), FacebookStandardEvent.LEAD),
                eventName(platform, request.loginRequestEventName(), FacebookStandardEvent.INITIATE_CHECKOUT),
                eventName(platform, request.loginSuccessEventName(), FacebookStandardEvent.COMPLETE_REGISTRATION),
                request.inAppOpenAllowed() == null || request.inAppOpenAllowed(),
                request.marketingAllowed() == null || request.marketingAllowed(),
                status);
    }

    /**
     * 获取或创建域名绑定。
     *
     * <p>先按域名、再按模板检查双方归属。数据库唯一键用于解决并发请求绕过先查后插窗口的问题。</p>
     */
    private PromotionDomain resolveDomain(ValidatedWrite value) {
        PromotionDomain domainByHost = mapper.selectActiveDomainByHost(value.domainHost());
        if (domainByHost != null) {
            return lockExistingDomain(domainByHost, value);
        }

        PromotionDomain domainByTemplate =
                mapper.selectActiveDomainByTemplateId(value.landingTemplateId());
        if (domainByTemplate != null) {
            return lockExistingDomain(domainByTemplate, value);
        }

        return insertDomain(value);
    }

    /** 锁定普通读命中的域名；若等待期间绑定已释放，则用 current read 重新解析。 */
    private PromotionDomain lockExistingDomain(PromotionDomain candidate, ValidatedWrite value) {
        PromotionDomain locked = mapper.selectActiveDomainByIdForUpdate(candidate.getId());
        if (locked != null) {
            requireSameTemplate(locked, value.landingTemplateId());
            requireSameDomain(locked, value.domainHost());
            return locked;
        }

        PromotionDomain currentByHost = mapper.selectActiveDomainByHostForUpdate(value.domainHost());
        if (currentByHost != null) {
            requireSameTemplate(currentByHost, value.landingTemplateId());
            return currentByHost;
        }
        PromotionDomain currentByTemplate =
                mapper.selectActiveDomainByTemplateIdForUpdate(value.landingTemplateId());
        if (currentByTemplate != null) {
            requireSameDomain(currentByTemplate, value.domainHost());
            return currentByTemplate;
        }
        return insertDomain(value);
    }

    /** 建立新的模板—域名关系；唯一键冲突后用 current read 返回并发赢家或稳定业务错误。 */
    private PromotionDomain insertDomain(ValidatedWrite value) {
        long now = System.currentTimeMillis();
        PromotionDomain created = new PromotionDomain();
        created.setDomainHost(value.domainHost());
        created.setLandingTemplateId(value.landingTemplateId());
        created.setCreatedBy(value.actorUserId());
        created.setUpdatedBy(value.actorUserId());
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            mapper.insertDomain(created);
            return created;
        } catch (DuplicateKeyException ex) {
            // 并发请求可能抢先占用域名，也可能抢先给模板绑定其他域名；分别查询后返回稳定业务错误。
            PromotionDomain concurrentByHost =
                    mapper.selectActiveDomainByHostForUpdate(value.domainHost());
            if (concurrentByHost != null) {
                requireSameTemplate(concurrentByHost, value.landingTemplateId());
                return concurrentByHost;
            }
            PromotionDomain concurrentByTemplate =
                    mapper.selectActiveDomainByTemplateIdForUpdate(value.landingTemplateId());
            if (concurrentByTemplate != null) {
                requireSameDomain(concurrentByTemplate, value.domainHost());
                return concurrentByTemplate;
            }
            // 可能由其他租户或历史软删记录占用；不暴露对方信息，也不误导为瞬时错误。
            throw new BusinessException(ErrorCode.CONFLICT, "访问域名或模板已被占用");
        }
    }

    /** 确保已有域名没有绑定到其他模板。 */
    private static void requireSameTemplate(PromotionDomain domain, Long templateId) {
        if (!templateId.equals(domain.getLandingTemplateId())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "访问域名已绑定其他模板，请更换域名: " + domain.getDomainHost());
        }
    }

    /** 确保模板没有绑定到其他域名；多个渠道可以复用同一条模板域名关系。 */
    private static void requireSameDomain(PromotionDomain domain, String domainHost) {
        if (!domainHost.equals(domain.getDomainHost())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "同一模板只能绑定一个访问域名，当前已绑定: " + domain.getDomainHost());
        }
    }

    /** 根据已校验参数构造渠道写入实体；创建人按当前需求取归属用户。 */
    private PromotionChannel buildChannel(
            ValidatedWrite value,
            String targetCountry,
            String preselectedCountry,
            Long domainId,
            long now) {
        PromotionChannel row = new PromotionChannel();
        row.setChannelName(value.channelName());
        row.setOwnerUserId(value.actorUserId());
        row.setPromotionDomainId(domainId);
        row.setThemeColor(value.themeColor());
        row.setIsAppDownloadShown(value.showAppDownload() == null
                ? null
                : (value.showAppDownload() ? 1 : 0));
        row.setTargetCountry(targetCountry);
        row.setPreselectedCountry(preselectedCountry);
        row.setPlatform(value.platform().code());
        row.setIsInAppOpenAllowed(value.inAppOpenAllowed() ? 1 : 0);
        row.setIsMarketingAllowed(value.marketingAllowed() ? 1 : 0);
        row.setStatus(value.status());
        row.setCreatedBy(value.actorUserId());
        row.setUpdatedBy(value.actorUserId());
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
            ValidatedWrite value,
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
        row.setCreatedBy(value.actorUserId());
        row.setUpdatedBy(value.actorUserId());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * 同步平台追踪配置。
     *
     * <p>非 CAPI 平台软删配置；CAPI 平台优先更新或复活旧行，不存在时才插入，
     * 从而兼容渠道在不同推广平台之间切换。</p>
     */
    private void syncTrackingConfig(
            ValidatedWrite value,
            PromotionChannel existing,
            long now) {
        Long channelId = existing.getId();
        if (!value.platform().capiSupported()) {
            mapper.softDeleteTrackingConfig(channelId, value.actorUserId(), now);
            return;
        }
        boolean platformChanged = !Integer.valueOf(value.platform().code()).equals(existing.getPlatform());
        boolean trackingIdCleared = !StringUtils.hasText(value.trackingId());
        if (!StringUtils.hasText(value.accessToken()) && (platformChanged || trackingIdCleared)) {
            // 不允许把旧平台 Token 带到新平台；显式清空追踪 ID 时也同步清除失去归属的密文。
            mapper.clearTrackingCredentials(channelId, value.actorUserId(), now);
        }
        PromotionChannelTrackingConfig tracking = buildTrackingConfig(value, channelId, now);
        if (mapper.updateTrackingConfig(tracking) == 0) {
            mapper.insertTrackingConfig(tracking);
        }
    }

    /** 查询当前租户内有效渠道，不存在或已软删时统一抛 NOT_FOUND。 */
    private PromotionChannel requireActiveChannel(Long id, DataScope scope) {
        PromotionChannel channel = mapper.selectActiveChannelById(id, scope);
        if (channel == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "渠道不存在或已删除: " + id);
        }
        return channel;
    }

    /**
     * Token 留空只代表“沿用同平台已有密文”，不能把 Facebook 凭据带到 TikTok，
     * 也不能在数据库本来没有 Token 时凭空创建只有追踪 ID 的无效配置。
     */
    private void requireReusableTrackingToken(ValidatedWrite value, PromotionChannel existing) {
        if (!value.platform().capiSupported()
                || StringUtils.hasText(value.accessToken())
                || !StringUtils.hasText(value.trackingId())) {
            return;
        }
        if (!Integer.valueOf(value.platform().code()).equals(existing.getPlatform())) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "切换推广平台并填写 Pixel/追踪 ID 时必须提供新 Access Token");
        }
        if (mapper.countReusableTrackingToken(
                existing.getId(), value.platform().code(), value.trackingId()) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "平台或 Pixel/追踪 ID 已变化，请填写新 Access Token");
        }
    }

    /** 查询当前租户内可用模板，不存在或停用时统一抛 NOT_FOUND。 */
    private PromotionLandingTemplate requireAvailableTemplate(Long id) {
        PromotionLandingTemplate template = mapper.selectAvailableTemplateById(id);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "绑定模板不存在或已停用: " + id);
        }
        return template;
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
        row.setTargetCountry(channel.getTargetCountry());
        row.setPreselectedCountry(channel.getPreselectedCountry());
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
    private void validateQuery(PromotionChannelQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "分页查询参数不能为空");
        }
        if (StringUtils.hasText(query.getTargetCountry())) {
            CountryOptionVO country = countryService.requireActiveOption(query.getTargetCountry(), true);
            query.setTargetCountry(country.value());
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

    /** 校验并统一主题色；创建使用产品默认值，编辑未传时交由 SQL 保留原值。 */
    private static String normalizeThemeColor(String value, boolean applyDefault) {
        if (value == null) {
            return applyDefault ? DEFAULT_THEME_COLOR : null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!THEME_COLOR_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "主题色必须是#开头的六位十六进制颜色");
        }
        return normalized;
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

    /** Facebook 只接受官方标准事件；其他既有 CAPI 平台保持原有长度校验行为。 */
    private static String eventName(
            PromotionPlatform platform,
            String value,
            FacebookStandardEvent defaultEvent) {
        if (platform == PromotionPlatform.FACEBOOK) {
            return FacebookStandardEvent.requireOrDefault(value, defaultEvent);
        }
        return StringUtils.hasText(value)
                ? requiredText(value, "上报事件", 64)
                : defaultEvent.code();
    }

    /** 校验所有业务主键均为正整数。 */
    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, field + "ID必须为正整数");
        }
    }

    /** 校验渠道状态只允许启用或禁用两个稳定值。 */
    private static int requireChannelStatus(Integer status) {
        if (status == null || (status != CHANNEL_STATUS_DISABLED && status != CHANNEL_STATUS_ENABLED)) {
            throw new BusinessException(ErrorCode.VALIDATION, "渠道状态必须是0(禁用)或1(启用)");
        }
        return status;
    }

    private record ValidatedWrite(
            String channelName,
            Long actorUserId,
            String targetCountry,
            Long landingTemplateId,
            String domainHost,
            String themeColor,
            Boolean showAppDownload,
            String preselectedCountry,
            PromotionPlatform platform,
            String trackingId,
            String accessToken,
            String leadEventName,
            String loginRequestEventName,
            String loginSuccessEventName,
            boolean inAppOpenAllowed,
            boolean marketingAllowed,
            int status) {
    }
}
