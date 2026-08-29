package com.armada.contact.task.service;

import com.armada.account.model.dto.AccountHyperlinkCandidateQuery;
import com.armada.account.model.vo.AccountHyperlinkCandidateVO;
import com.armada.account.service.AccountHyperlinkCandidateService;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.port.HyperlinkPrivateCapabilityPort;
import com.armada.hyperlink.task.service.HyperlinkAccountFilterNormalizer;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * 通讯录营销的账号圈选，直接复用超链任务已经落地的那套账号候选能力。
 *
 * <p>不再自建一套 WHERE：账号筛选的全部条件（大洲、存活天数、允许拉群、导入方式、导入批次、
 * 轮换状态等）都在 {@code AccountHyperlinkCandidateService} 里真下推 SQL，两个菜单共用一份，
 * 条件不会漂移。本类只负责把任务里存的筛选 JSON 变成查询，并补上通讯录侧的默认值。</p>
 *
 * <p>私聊能力口径也与超链任务共用 {@link HyperlinkPrivateCapabilityPort}：
 * 「这个协议后端能不能发私聊」是同一个物理事实，不该有两个开关。
 * 这意味着 {@code armada.hyperlink.private-capable-backends} 没配时通讯录任务同样圈不到号。</p>
 */
@Component
public class ContactAccountSelector {

    /** 筛选快照的 schema 版本，归一化器要求恒为 1。 */
    private static final int FILTER_SCHEMA_VERSION = 1;

    /** 账号候选查询，与超链任务同一实现。 */
    private final AccountHyperlinkCandidateService candidateService;

    /** 筛选条件归一化，与超链任务同一实现。 */
    private final HyperlinkAccountFilterNormalizer filterNormalizer;

    /** 协议后端私聊能力判定，与超链任务同一实现。 */
    private final HyperlinkPrivateCapabilityPort capabilityPort;

    /** 筛选 JSON 解析。 */
    private final ObjectMapper objectMapper;

    /** 相对时间条件（存活天数、注册天数）的观察时刻来源。 */
    private final Clock clock;

    /**
     * 创建通讯录账号圈选服务。
     *
     * @param candidateService 账号候选查询
     * @param filterNormalizer 筛选条件归一化
     * @param capabilityPort 协议后端私聊能力判定
     * @param objectMapper JSON 解析器
     * @param clock 时钟
     */
    public ContactAccountSelector(AccountHyperlinkCandidateService candidateService,
                                  HyperlinkAccountFilterNormalizer filterNormalizer,
                                  HyperlinkPrivateCapabilityPort capabilityPort,
                                  ObjectMapper objectMapper,
                                  Clock clock) {
        this.candidateService = candidateService;
        this.filterNormalizer = filterNormalizer;
        this.capabilityPort = capabilityPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 按筛选条件圈出可发送账号，按优先级降序取前 {@code limit} 个。
     *
     * @param filterJson 任务里存的筛选 JSON，空表示不限
     * @param limit 最多返回多少个账号
     * @return 命中的账号候选
     */
    public List<AccountHyperlinkCandidateVO> select(String filterJson, int limit) {
        List<String> backends = capableBackends();
        if (backends.isEmpty()) {
            return List.of();
        }
        return candidateService.selectCandidates(query(filterJson, backends), null, null, limit);
    }

    /**
     * 试算命中账号数。走的是与 {@link #select} 完全相同的条件，界面显示的数字不会骗人。
     *
     * @param filterJson 前端提交的原始筛选 JSON，空表示不限
     * @return 命中账号数
     */
    public int count(String filterJson) {
        List<String> backends = capableBackends();
        if (backends.isEmpty()) {
            return 0;
        }
        return candidateService.countCandidates(query(filterJson, backends));
    }

    /**
     * 把筛选 JSON 归一化成可入库的形状。存库前调用，避免把前端 JSON 原样落库。
     *
     * @param filterJson 前端提交的原始筛选 JSON
     * @return 归一化后的 JSON 字符串
     */
    public String normalizeToJson(String filterJson) {
        try {
            return objectMapper.writeValueAsString(readFilter(filterJson));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号筛选条件无法序列化");
        }
    }

    private AccountHyperlinkCandidateQuery query(String filterJson, List<String> backends) {
        HyperlinkAccountFilterDTO filter = readFilter(filterJson);
        return new AccountHyperlinkCandidateQuery(
                filter.countryIso2s(), filter.excludeCountryIso2s(), filter.continent(),
                filter.groupIds(), filter.channelIds(), filter.protocolId(), filter.onlineStatus(),
                filter.rotationStatus(), filter.accountType(), filter.platform(), filter.widType(),
                filter.importMode(), filter.groupInviteAllowed(), filter.phone(),
                filter.importBatchId(), filter.source(), filter.friendCountMin(),
                filter.friendCountMax(), filter.contactNamedNumMin(), filter.contactNamedNumMax(),
                filter.retentionDaysMin(), filter.retentionDaysMax(),
                filter.registerDaysMin(), filter.registerDaysMax(),
                filter.createdAtFrom(), filter.createdAtTo(),
                backends, clock.millis());
    }

    /**
     * 解析并归一化筛选 JSON。
     *
     * <p>空筛选的语义是「未限制（全部有效账号）」，竞品提交的就是 {@code {}}；
     * 归一化器要求 {@code filterSchemaVersion=1}，这里替它补上，不让前端关心版本号。</p>
     */
    private HyperlinkAccountFilterDTO readFilter(String filterJson) {
        String json = filterJson == null || filterJson.isBlank() ? "{}" : filterJson;
        HyperlinkAccountFilterDTO parsed;
        try {
            parsed = objectMapper.readValue(json, HyperlinkAccountFilterDTO.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号筛选条件无法解析");
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号筛选条件含未知字段");
        }
        return filterNormalizer.normalize(withSchemaVersion(parsed));
    }

    private static HyperlinkAccountFilterDTO withSchemaVersion(HyperlinkAccountFilterDTO value) {
        if (Integer.valueOf(FILTER_SCHEMA_VERSION).equals(value.filterSchemaVersion())) {
            return value;
        }
        return new HyperlinkAccountFilterDTO(FILTER_SCHEMA_VERSION,
                value.countryIso2s(), value.excludeCountryIso2s(), value.continent(),
                value.groupIds(), value.channelIds(), value.protocolId(), value.onlineStatus(),
                value.rotationStatus(), value.accountType(), value.platform(), value.widType(),
                value.importMode(), value.groupInviteAllowed(), value.phone(),
                value.importBatchId(), value.source(), value.friendCountMin(),
                value.friendCountMax(), value.contactNamedNumMin(), value.contactNamedNumMax(),
                value.retentionDaysMin(), value.retentionDaysMax(),
                value.registerDaysMin(), value.registerDaysMax(),
                value.createdAtFrom(), value.createdAtTo());
    }

    private List<String> capableBackends() {
        return Arrays.stream(ProtocolBackend.values())
                .filter(backend -> capabilityPort.supports(backend, backend.name()))
                .map(ProtocolBackend::name)
                .sorted()
                .toList();
    }
}
