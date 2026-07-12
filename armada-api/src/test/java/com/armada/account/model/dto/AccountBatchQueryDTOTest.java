package com.armada.account.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 批量账号查询条件契约测试。
 */
class AccountBatchQueryDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void jsonIgnoresPaginationAndConvertsSupportedFilters() throws Exception {
        AccountBatchQueryDTO dto = objectMapper.readValue("""
                {"loginState":2,"country":" 美国 ","accountGroupId":7,"page":9,"pageSize":500}
                """, AccountBatchQueryDTO.class);

        AccountQuery query = dto.toAccountQuery();

        assertThat(query.getLoginState()).isEqualTo(2);
        assertThat(query.getCountry()).isEqualTo("美国");
        assertThat(query.getAccountGroupId()).isEqualTo(7L);
        assertThat(query.getPage()).isEqualTo(1);
        assertThat(query.getPageSize()).isEqualTo(10);
    }

    @Test
    void emptyJsonRepresentsAllActiveTenantAccounts() throws Exception {
        AccountBatchQueryDTO dto = objectMapper.readValue("{}", AccountBatchQueryDTO.class);

        AccountQuery query = dto.toAccountQuery();

        assertThat(query.getKeyword()).isNull();
        assertThat(query.getLoginState()).isNull();
        assertThat(query.getCountry()).isNull();
    }
}
