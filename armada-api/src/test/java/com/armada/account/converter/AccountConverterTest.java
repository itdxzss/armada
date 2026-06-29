package com.armada.account.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.vo.AccountGroupVO;
import com.armada.account.model.vo.AccountGroupVoRow;
import com.armada.account.model.vo.AccountListVO;
import com.armada.account.model.vo.AccountListVoRow;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * AccountConverter 转换规则单测。
 */
class AccountConverterTest {

    private final AccountConverter converter = Mappers.getMapper(AccountConverter.class);

    @Test
    void toGroupVO_mapsGroupStatusCounters() {
        AccountGroupVoRow row = new AccountGroupVoRow();
        row.setId(1L);
        row.setName("分组A");
        row.setSystemBuiltin(0);
        row.setAccountCount(7L);
        row.setOnlineCount(3L);
        row.setRiskCount(2L);
        row.setBannedCount(1L);

        AccountGroupVO vo = converter.toGroupVO(row);

        assertThat(vo.accountCount()).isEqualTo(7L);
        assertThat(vo.onlineCount()).isEqualTo(3L);
        assertThat(vo.riskCount()).isEqualTo(2L);
        assertThat(vo.bannedCount()).isEqualTo(1L);
    }

    @Test
    void toAccountListVO_mapsProxyCountryToCountryAndIpSource() {
        AccountListVoRow row = new AccountListVoRow();
        row.setId(9L);
        row.setProxyCountry("印度");
        row.setIpSource("iproyal");

        AccountListVO vo = converter.toAccountListVO(row);

        assertThat(vo.country()).isEqualTo("印度");
        assertThat(vo.ipSource()).isEqualTo("iproyal");
    }
}
