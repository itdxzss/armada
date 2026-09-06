package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.dto.DeviceImportDTO;
import com.armada.account.model.dto.DeviceImportDefaults;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.account.service.impl.DeviceImportServiceImpl;
import com.armada.boot.security.DeviceIngestTokens;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DeviceImportTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceImportServiceTest {

    private final AccountImportService imports = mock(AccountImportService.class);
    private final AccountImportDetailMapper details = mock(AccountImportDetailMapper.class);
    private final DeviceImportService service = new DeviceImportServiceImpl(
            new AccountImportParser(), imports, details);
    private DeviceImportDefaults defaults;

    @BeforeEach
    void setUp() {
        String token = DeviceImportTestData.token();
        defaults = new DeviceIngestTokens(DeviceImportTestData.clients(token, 7, 11)).resolve(token).orElseThrow();
        TenantContext.set(7L);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void validPayloadUsesExistingImportAndReturnsQueued() {
        String payload = DeviceImportTestData.payload("999000000001");
        when(imports.importDeviceAccount(eq(defaults.metadata()), any())).thenReturn(batch(1, 0));
        var result = service.importAccount(new DeviceImportDTO("999000000001", payload), defaults);
        assertThat(result.batchId()).isEqualTo(123);
        assertThat(result.onlinePhase()).isEqualTo("QUEUED");
    }

    @Test
    void rejectsMalformedAndMismatchedPayloadBeforeWriting() {
        String phone = "999000000001";
        String valid = DeviceImportTestData.payload(phone);
        for (String payload : new String[]{"", "[]", "{}", valid + "\n", valid + "\u2028",
                valid + " {}", valid.replace("\"jid\":", "\"jid\":\"999000000002\",\"jid\":"),
                DeviceImportTestData.payload("999000000002")}) {
            assertThatThrownBy(() -> service.importAccount(new DeviceImportDTO(phone, payload), defaults))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(imports);
    }

    @Test
    void wrongTenantCannotUseDefaults() {
        TenantContext.set(8L);
        assertThatThrownBy(() -> service.importAccount(
                new DeviceImportDTO("999000000001", DeviceImportTestData.payload("999000000001")), defaults))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(imports, details);
    }

    @Test
    void existingImportIsConflictEvenWhenBulkImportWouldReturnNormally() {
        when(imports.importDeviceAccount(any(), any())).thenReturn(batch(0, 1));
        assertThatThrownBy(() -> service.importAccount(
                new DeviceImportDTO("999000000001", DeviceImportTestData.payload("999000000001")), defaults))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code()));
    }

    @Test
    void pendingDetailBlocksReimportBeforeBulkImport() {
        when(details.existsPendingByPhone("999000000001", 1, 1, 2)).thenReturn(true);
        assertThatThrownBy(() -> service.importAccount(
                new DeviceImportDTO("999000000001", DeviceImportTestData.payload("999000000001")), defaults))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code()));
        verifyNoInteractions(imports);
    }

    private AccountImportBatchVO batch(int imported, int duplicate) {
        return new AccountImportBatchVO(123L, "device-import", 3, 2, 2, null, "mixed", 1,
                imported, duplicate, 0, null, null, null, 2, System.currentTimeMillis());
    }
}
