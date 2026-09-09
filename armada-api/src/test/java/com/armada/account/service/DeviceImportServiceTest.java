package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.dto.DeviceImportDTO;
import com.armada.account.model.dto.DeviceImportDefaults;
import com.armada.account.model.entity.AccountGroup;
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
    private final AccountGroupMapper groups = mock(AccountGroupMapper.class);
    private final DeviceImportService service = new DeviceImportServiceImpl(
            new AccountImportParser(), imports, details, groups);
    private DeviceImportDefaults defaults;

    @BeforeEach
    void setUp() {
        String token = DeviceImportTestData.token();
        defaults = new DeviceIngestTokens(DeviceImportTestData.clients(token, 7)).resolve(token).orElseThrow();
        TenantContext.set(7L);
        when(groups.selectActiveByName("手机上传组")).thenReturn(group(11L, "手机上传组"));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void validPayloadUsesExistingImportAndWaitsForLogout() {
        String payload = DeviceImportTestData.payload("999000000001");
        when(imports.importDeviceAccount(any(), any())).thenReturn(batch(1, 0));
        when(details.holdDeviceImport(123L, 1, 4)).thenReturn(1);
        var result = service.importAccount(new DeviceImportDTO(" 手机上传组 ", "999000000001", payload), defaults);
        assertThat(result.batchId()).isEqualTo(123);
        assertThat(result.onlinePhase()).isEqualTo("WAITING_LOGOUT");
        var metadata = org.mockito.ArgumentCaptor.forClass(AccountImportDTO.class);
        verify(imports).importDeviceAccount(metadata.capture(), any());
        assertThat(metadata.getValue().accountGroupId()).isEqualTo(11L);
        assertThat(metadata.getValue().deviceOs()).isEqualTo(defaults.metadata().deviceOs());
        assertThat(metadata.getValue().accountType()).isEqualTo(defaults.metadata().accountType());
        assertThat(metadata.getValue().ipAllocationMode()).isEqualTo(defaults.metadata().ipAllocationMode());
        assertThat(defaults.metadata().accountGroupId()).isNull();
    }

    @Test
    void missingOrBlankGroupNameCannotFallBackToServerDefault() {
        for (String groupName : new String[]{null, "", "   ", "组".repeat(101)}) {
            assertThatThrownBy(() -> service.importAccount(new DeviceImportDTO(groupName, "999000000001",
                    DeviceImportTestData.payload("999000000001")), defaults))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code()));
        }
        verifyNoInteractions(imports, details, groups);
    }

    @Test
    void unknownGroupNameIsReportedBeforeImport() {
        assertThatThrownBy(() -> service.importAccount(new DeviceImportDTO("不存在的分组", "999000000001",
                DeviceImportTestData.payload("999000000001")), defaults))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.DEVICE_IMPORT_GROUP_NOT_FOUND.code()));
        verify(groups).selectActiveByName("不存在的分组");
        verifyNoInteractions(imports, details);
    }

    @Test
    void rejectsMalformedAndMismatchedPayloadBeforeWriting() {
        String phone = "999000000001";
        String valid = DeviceImportTestData.payload(phone);
        for (String payload : new String[]{"", "[]", "{}", valid + "\n", valid + "\u2028",
                valid + " {}", valid.replace("\"jid\":", "\"jid\":\"999000000002\",\"jid\":"),
                DeviceImportTestData.payload("999000000002")}) {
            assertThatThrownBy(() -> service.importAccount(new DeviceImportDTO("手机上传组", phone, payload), defaults))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(imports);
    }

    @Test
    void wrongTenantCannotUseDefaults() {
        TenantContext.set(8L);
        assertThatThrownBy(() -> service.importAccount(
                new DeviceImportDTO("手机上传组", "999000000001", DeviceImportTestData.payload("999000000001")), defaults))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(imports, details);
    }

    @Test
    void existingImportIsConflictEvenWhenBulkImportWouldReturnNormally() {
        when(imports.importDeviceAccount(any(), any())).thenReturn(batch(0, 1));
        assertThatThrownBy(() -> service.importAccount(
                new DeviceImportDTO("手机上传组", "999000000001", DeviceImportTestData.payload("999000000001")), defaults))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code()));
    }

    @Test
    void pendingDetailBlocksReimportBeforeBulkImport() {
        when(details.existsPendingByPhone("999000000001", 1, 1, 2, 4)).thenReturn(true);
        assertThatThrownBy(() -> service.importAccount(
                new DeviceImportDTO("手机上传组", "999000000001", DeviceImportTestData.payload("999000000001")), defaults))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code()));
        verifyNoInteractions(imports);
    }

    private AccountImportBatchVO batch(int imported, int duplicate) {
        return new AccountImportBatchVO(123L, "device-import", 3, 2, 2, null, "mixed", 1,
                imported, duplicate, 0, null, null, null, 2, System.currentTimeMillis());
    }

    private static AccountGroup group(long id, String name) {
        AccountGroup group = new AccountGroup();
        group.setId(id);
        group.setName(name);
        return group;
    }
}
