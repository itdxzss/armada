package com.armada.hyperlink.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyCreateDTO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyDetailVO;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 超链策略 camelCase 请求与弱引用响应字段合同。 */
class HyperlinkStrategyApiShapeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createRequestDeserializesOnlyFrozenTaskConfigurationFields() throws Exception {
        HyperlinkStrategyCreateDTO request = objectMapper.readValue("""
                {
                  "name":"巴西周期策略",
                  "taskMode":"cycle",
                  "accountFilter":{"filterSchemaVersion":1,"countryIso2s":["BR"]},
                  "maxExecutingAccounts":10,
                  "maxUseAccounts":50,
                  "maxSendPerAccount":100,
                  "cycleIntervalMinutes":60,
                  "enabled":true
                }
                """, HyperlinkStrategyCreateDTO.class);

        assertThat(request.name()).isEqualTo("巴西周期策略");
        assertThat(request.taskMode()).isEqualTo("cycle");
        assertThat(request.accountFilter().countryIso2s()).containsExactly("BR");
        assertThat(request.maxExecutingAccounts()).isEqualTo(10);
        assertThat(request.maxUseAccounts()).isEqualTo(50);
        assertThat(request.maxSendPerAccount()).isEqualTo(100);
        assertThat(request.cycleIntervalMinutes()).isEqualTo(60);
        assertThat(request.enabled()).isTrue();
    }

    @Test
    void detailUsesTaskContractNamesAndDoesNotExposeDatabaseAliases() {
        HyperlinkAccountFilterDTO filter = emptyFilter();
        HyperlinkStrategyDetailVO detail = new HyperlinkStrategyDetailVO(
                301L, "即时策略", "instant", filter, 10, 0, 0, 0,
                true, 1, 7L, 100L, 200L);

        JsonNode json = objectMapper.valueToTree(detail);

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "name", "taskMode", "accountFilter", "maxExecutingAccounts",
                "maxUseAccounts", "maxSendPerAccount", "cycleIntervalMinutes", "enabled",
                "version", "createdBy", "createdAt", "updatedAt");
        assertThat(json.has("strategyName")).isFalse();
        assertThat(json.has("taskType")).isFalse();
        assertThat(json.has("concurrentNum")).isFalse();
        assertThat(json.has("accountMaxSendNum")).isFalse();
        assertThat(json.has("concurrentAccounts")).isFalse();
    }

    private static HyperlinkAccountFilterDTO emptyFilter() {
        return new HyperlinkAccountFilterDTO(
                1, List.of(), List.of(), null, List.of(), List.of(), null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }
}
