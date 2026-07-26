package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountGroup;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountGroupMapperUnitTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void selectByIdsForUpdateDelegatesWithTenantFromContext() {
        AccountGroupMapper mapper = mock(AccountGroupMapper.class, CALLS_REAL_METHODS);
        List<Long> groupIds = List.of(11L, 12L);
        AccountGroup group = new AccountGroup();
        group.setId(11L);
        TenantContext.set(7L);
        when(mapper.selectByTenantAndIdsForUpdate(7L, groupIds)).thenReturn(List.of(group));

        List<AccountGroup> result = mapper.selectByIdsForUpdate(groupIds);

        assertThat(result).containsExactly(group);
        verify(mapper).selectByTenantAndIdsForUpdate(7L, groupIds);
    }

    @Test
    void selectByIdsForUpdateRejectsMissingTenantContext() {
        AccountGroupMapper mapper = mock(AccountGroupMapper.class, CALLS_REAL_METHODS);

        assertThatThrownBy(() -> mapper.selectByIdsForUpdate(List.of(11L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.TENANT_MISSING.code()));
    }
}
