package com.armada.hyperlink.task.service;

import com.armada.account.model.dto.AccountHyperlinkCandidateQuery;
import com.armada.account.model.vo.AccountHyperlinkCandidateVO;
import com.armada.account.service.AccountHyperlinkCandidateService;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.port.HyperlinkPrivateCapabilityPort;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/** 解析任务筛选快照，并经账号域 Service 选择具备 PRIVATE 能力的候选。 */
@Service
public class HyperlinkAccountCandidateSelector {

    private final AccountHyperlinkCandidateService accountService;
    private final HyperlinkPrivateCapabilityPort capabilityPort;
    private final ObjectMapper objectMapper;
    private final HyperlinkAccountFilterNormalizer accountFilterNormalizer;

    public HyperlinkAccountCandidateSelector(AccountHyperlinkCandidateService accountService,
            HyperlinkPrivateCapabilityPort capabilityPort, ObjectMapper objectMapper,
            HyperlinkAccountFilterNormalizer accountFilterNormalizer) {
        this.accountService = accountService;
        this.capabilityPort = capabilityPort;
        this.objectMapper = objectMapper;
        this.accountFilterNormalizer = accountFilterNormalizer;
    }

    /**
     * 按任务冻结快照选择账号，并排除尚未通过协议 PRIVATE 能力门禁的账号。
     *
     * @param task 当前任务
     * @param afterPriority 上一页末行的账号优先级；首页为空
     * @param afterAccountId 上一页末行的账号 ID；首页为空
     * @param limit 最大候选数
     * @param observedAt 留存天数统一观察时间
     * @return 可进入任务用量快照的账号域只读投影
     */
    public List<AccountHyperlinkCandidateVO> select(HyperlinkTask task,
            Integer afterPriority, Long afterAccountId, int limit, long observedAt) {
        HyperlinkAccountFilterDTO filter = readFilter(task.getAccountFilter());
        List<String> capableBackends = capableBackends();
        if (capableBackends.isEmpty()) {
            return List.of();
        }
        return accountService.selectCandidates(query(filter, capableBackends, observedAt),
                afterPriority, afterAccountId, limit);
    }

    /** 按与运行选号相同的 SQL 条件在数据库直接试算匹配账号数。 */
    public int count(HyperlinkAccountFilterDTO input, long observedAt) {
        HyperlinkAccountFilterDTO filter = accountFilterNormalizer.normalize(input);
        List<String> capableBackends = capableBackends();
        if (capableBackends.isEmpty()) {
            return 0;
        }
        return accountService.countCandidates(query(filter, capableBackends, observedAt));
    }

    /** 返回当前租户已通过 PRIVATE 能力门禁的协议节点总数；不随账号范围筛选变化。 */
    public int protocolCount() {
        List<String> capableBackends = capableBackends();
        return capableBackends.isEmpty() ? 0 : accountService.countProtocols(capableBackends);
    }

    /** 当前租户正常账号真实协议 ID；仅保留已通过 PRIVATE 能力门禁的后端。 */
    public List<String> protocolIds() {
        List<String> capableBackends = capableBackends();
        return capableBackends.isEmpty() ? List.of() : accountService.listProtocolIds(capableBackends);
    }

    private AccountHyperlinkCandidateQuery query(HyperlinkAccountFilterDTO filter,
            List<String> capableBackends, long observedAt) {
        return new AccountHyperlinkCandidateQuery(
                filter.countryIso2s(), filter.excludeCountryIso2s(), filter.continent(),
                filter.groupIds(), filter.channelIds(), filter.protocolId(), filter.onlineStatus(),
                filter.rotationStatus(), filter.accountType(), filter.platform(), filter.widType(),
                filter.importMode(), filter.groupInviteAllowed(), filter.phone(),
                filter.importBatchId(), filter.source(), filter.friendCountMin(),
                filter.friendCountMax(), filter.retentionDaysMin(), filter.retentionDaysMax(),
                filter.registerDaysMin(), filter.registerDaysMax(),
                filter.createdAtFrom(), filter.createdAtTo(),
                capableBackends, observedAt);
    }

    private List<String> capableBackends() {
        return Arrays.stream(ProtocolBackend.values())
                .filter(backend -> capabilityPort.supports(backend, backend.name()))
                .map(ProtocolBackend::name)
                .sorted()
                .toList();
    }

    private HyperlinkAccountFilterDTO readFilter(String json) {
        try {
            HyperlinkAccountFilterDTO filter = objectMapper.readValue(
                    json, HyperlinkAccountFilterDTO.class);
            return accountFilterNormalizer.normalize(filter);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号筛选快照无法解析");
        }
    }

}
