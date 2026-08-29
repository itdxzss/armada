package com.armada.account.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 账号圈选条件解析的纯类测试。 */
class AccountFilterCriteriaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void treatsEmptyObjectAsUnrestricted() {
        AccountFilterCriteria criteria = AccountFilterCriteria.parse("{}", mapper);

        assertThat(criteria.isUnrestricted()).isTrue();
    }

    @Test
    void treatsNullJsonAsUnrestricted() {
        AccountFilterCriteria criteria = AccountFilterCriteria.parse(null, mapper);

        assertThat(criteria.isUnrestricted()).isTrue();
    }

    @Test
    void parsesCountryAndExcludeCountryArrays() {
        String json = "{\"countryIso2s\":[\"IN\",\"BR\"],\"excludeCountryIso2s\":[\"CN\"]}";

        AccountFilterCriteria criteria = AccountFilterCriteria.parse(json, mapper);

        assertThat(criteria.countryIso2s()).containsExactly("IN", "BR");
        assertThat(criteria.excludeCountryIso2s()).containsExactly("CN");
        assertThat(criteria.isUnrestricted()).isFalse();
    }

    @Test
    void parsesIdArraysAndScalarFields() {
        String json = "{\"groupIds\":[7,9],\"channelIds\":[3],\"protocolId\":\"web\","
                + "\"accountType\":1,\"phone\":\"8613\"}";

        AccountFilterCriteria criteria = AccountFilterCriteria.parse(json, mapper);

        assertThat(criteria.groupIds()).containsExactly(7L, 9L);
        assertThat(criteria.channelIds()).containsExactly(3L);
        assertThat(criteria.protocolId()).isEqualTo("web");
        assertThat(criteria.accountType()).isEqualTo(1);
        assertThat(criteria.phone()).isEqualTo("8613");
    }

    @Test
    void parsesTheFiltersBackedByRealColumns() {
        String json = "{\"onlineStatus\":1,\"deviceOs\":2,\"errorCode\":\"403\","
                + "\"createdAtFrom\":1700000000000,\"createdAtTo\":1800000000000}";

        AccountFilterCriteria criteria = AccountFilterCriteria.parse(json, mapper);

        assertThat(criteria.onlineStatus()).isEqualTo(1);
        assertThat(criteria.deviceOs()).isEqualTo(2);
        assertThat(criteria.errorCode()).isEqualTo("403");
        assertThat(criteria.createdAtFrom()).isEqualTo(1_700_000_000_000L);
        assertThat(criteria.createdAtTo()).isEqualTo(1_800_000_000_000L);
    }

    @Test
    void parsesNumericRangeBounds() {
        String json = "{\"friendCountMin\":10,\"friendCountMax\":500,"
                + "\"registerDaysMin\":3,\"registerDaysMax\":90}";

        AccountFilterCriteria criteria = AccountFilterCriteria.parse(json, mapper);

        assertThat(criteria.friendCountMin()).isEqualTo(10L);
        assertThat(criteria.friendCountMax()).isEqualTo(500L);
        assertThat(criteria.registerDaysMin()).isEqualTo(3L);
        assertThat(criteria.registerDaysMax()).isEqualTo(90L);
    }

    @Test
    void dropsKeysWithoutPushdownSupport() {
        // continent / platform / widType 在 armada 没有可下推的列，解析后必须丢弃，
        // 不能悄悄留在条件里让人以为筛选生效了
        String json = "{\"continent\":\"AS\",\"platform\":\"android\",\"widType\":\"lid\"}";

        AccountFilterCriteria criteria = AccountFilterCriteria.parse(json, mapper);

        assertThat(criteria.isUnrestricted()).isTrue();
    }

    @Test
    void treatsUnparseableJsonAsUnrestricted() {
        // 坏 JSON 等价于「不限定」，不让一个筛选字段把整个圈号搞崩
        AccountFilterCriteria criteria = AccountFilterCriteria.parse("{not json", mapper);

        assertThat(criteria.isUnrestricted()).isTrue();
    }
}
