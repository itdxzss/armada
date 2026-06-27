package com.armada.resource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.resource.converter.IpProxyConverter;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.dto.IpProxyImportDTO;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.vo.IpProxyImportResultVO;
import com.armada.resource.service.impl.IpProxyServiceImpl;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IP 代理导入业务单测(mock mapper/converter,验导入统计语义)。
 */
@ExtendWith(MockitoExtension.class)
class IpProxyServiceImplTest {

    @Mock
    private IpProxyMapper mapper;

    @Mock
    private IpProxyConverter converter;

    @InjectMocks
    private IpProxyServiceImpl service;

    /** 构造合法的导入 DTO(协议=1/HTTP,来源非空)。 */
    private IpProxyImportDTO dto(String text) {
        return new IpProxyImportDTO("中国", 1, "供应商A", text);
    }

    @Test
    void importProxies_allNew_returnsCorrectStats() {
        // 所有行全新插入
        when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);

        IpProxyImportResultVO result = service.importProxies(dto("1.1.1.1:8080:user1:pass1\n2.2.2.2:8080:user2:pass2"));

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.insertedRows()).isEqualTo(2);
        assertThat(result.skippedRows()).isEqualTo(0);
        assertThat(result.failedRows()).isEqualTo(0);
        assertThat(result.errors()).isEmpty();
        verify(mapper, times(2)).insert(any());
    }

    @Test
    void importProxies_dbDuplicate_countedAsSkipped() {
        // 第一行库内已存在(countActive=1),第二行新增
        when(mapper.countActiveByFullTuple("1.1.1.1", 8080, "user1", "pass1")).thenReturn(1L);
        when(mapper.countActiveByFullTuple("2.2.2.2", 9090, "user2", "pass2")).thenReturn(0L);

        IpProxyImportResultVO result = service.importProxies(dto("1.1.1.1:8080:user1:pass1\n2.2.2.2:9090:user2:pass2"));

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.insertedRows()).isEqualTo(1);
        assertThat(result.skippedRows()).isEqualTo(1);
        assertThat(result.failedRows()).isEqualTo(0);
        verify(mapper, times(1)).insert(any());
    }

    @Test
    void importProxies_batchDuplicate_countedAsSkipped() {
        // 同一行出现两次:批内去重,只落库一次
        when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);

        IpProxyImportResultVO result = service.importProxies(dto("1.1.1.1:8080:user1:pass1\n1.1.1.1:8080:user1:pass1"));

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.insertedRows()).isEqualTo(1);
        assertThat(result.skippedRows()).isEqualTo(1);   // 批内重复
        assertThat(result.failedRows()).isEqualTo(0);
        verify(mapper, times(1)).insert(any());
    }

    @Test
    void importProxies_badFormat_countedAsFailed() {
        // 格式错误行不触发 insert;第二行合法但此处只测格式失败行的统计
        // "bad-line" 缺少冒号分隔符,解析必然失败
        IpProxyImportResultVO result = service.importProxies(dto("bad-line"));

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.failedRows()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("第 1 行");
        verify(mapper, never()).insert(any());
    }

    @Test
    void importProxies_emptyContent_throwsValidation() {
        assertThatThrownBy(() -> service.importProxies(dto("   ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("导入内容不能为空");
        verify(mapper, never()).insert(any());
    }

    @Test
    void importProxies_nullProtocol_throwsValidation() {
        IpProxyImportDTO dtoNullProtocol = new IpProxyImportDTO("中国", null, "供应商A", "1.1.1.1:8080:u:p");
        assertThatThrownBy(() -> service.importProxies(dtoNullProtocol))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("代理类型不能为空");
        verify(mapper, never()).insert(any());
    }

    @Test
    void importProxies_emptySource_throwsValidation() {
        IpProxyImportDTO dtoNoSource = new IpProxyImportDTO("中国", 1, "", "1.1.1.1:8080:u:p");
        assertThatThrownBy(() -> service.importProxies(dtoNoSource))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("来源不能为空");
        verify(mapper, never()).insert(any());
    }

    @Test
    void importProxies_skipsBlankLines() {
        when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);

        IpProxyImportResultVO result = service.importProxies(dto("\n1.1.1.1:8080:u:p\n\n"));

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.insertedRows()).isEqualTo(1);
    }

    @Test
    void allocateOnlineEndpoint_releasesOldBindingSelectsIdleAndMarksUsing() {
        TenantContext.set(1L);
        try {
            IpProxy row = idleProxy();
            when(mapper.releaseByAccount(
                    eq(100L),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(1);
            when(mapper.selectOneIdleForUpdate(1L, IpProxyStatus.IDLE.code())).thenReturn(row);
            when(mapper.markUsingAndBind(
                    eq(10L),
                    eq(100L),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(1);

            IpProxyAllocation allocation = service.allocateOnlineEndpoint(100L);

            assertThat(allocation.proxyId()).isEqualTo(10L);
            assertThat(allocation.endpoint().protocolCode()).isEqualTo(ProxyEndpoint.PROTOCOL_SOCKS5);
            assertThat(allocation.endpoint().host()).isEqualTo("geo.iproyal.com");
            assertThat(allocation.endpoint().port()).isEqualTo(12321);
            assertThat(allocation.endpoint().credentials().username()).isEqualTo("user1");
            assertThat(allocation.endpoint().credentials().password())
                    .isEqualTo("pass1_country-in_session-Abc12345_lifetime-1h");
            assertThat(allocation.endpoint().country()).isEqualTo("印度");

            InOrder inOrder = org.mockito.Mockito.inOrder(mapper);
            inOrder.verify(mapper).releaseByAccount(
                    eq(100L), eq(IpProxyStatus.IDLE.code()), eq(IpProxyStatus.IN_USE.code()), anyLong());
            inOrder.verify(mapper).selectOneIdleForUpdate(1L, IpProxyStatus.IDLE.code());
            inOrder.verify(mapper).markUsingAndBind(
                    eq(10L), eq(100L), eq(IpProxyStatus.IDLE.code()), eq(IpProxyStatus.IN_USE.code()), anyLong());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void allocateOnlineEndpoint_nullAccountId_throwsValidationBeforeMapper() {
        TenantContext.set(1L);
        try {
            assertThatThrownBy(() -> service.allocateOnlineEndpoint(null))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                        assertThat(ex.getMessage()).contains("账号 ID 不能为空");
                    });
            verifyNoInteractions(mapper);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void allocateOnlineEndpoint_missingTenant_throwsTenantMissingBeforeMapper() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.allocateOnlineEndpoint(100L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.TENANT_MISSING.code());
                    assertThat(ex.getMessage()).contains("缺少租户上下文");
                });
        verifyNoInteractions(mapper);
    }

    @Test
    void allocateOnlineEndpoint_noIdleProxy_throwsValidationBeforeMarkUsing() {
        TenantContext.set(1L);
        try {
            when(mapper.releaseByAccount(
                    eq(100L),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(0);
            when(mapper.selectOneIdleForUpdate(1L, IpProxyStatus.IDLE.code())).thenReturn(null);

            assertThatThrownBy(() -> service.allocateOnlineEndpoint(100L))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                        assertThat(ex.getMessage()).contains("暂无空闲代理");
                    });
            verify(mapper, never()).markUsingAndBind(any(), any(), anyInt(), anyInt(), anyLong());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void allocateOnlineEndpoint_markConflict_throwsConflict() {
        TenantContext.set(1L);
        try {
            when(mapper.releaseByAccount(
                    eq(100L),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(0);
            when(mapper.selectOneIdleForUpdate(1L, IpProxyStatus.IDLE.code())).thenReturn(idleProxy());
            when(mapper.markUsingAndBind(
                    eq(10L),
                    eq(100L),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(0);

            assertThatThrownBy(() -> service.allocateOnlineEndpoint(100L))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code());
                        assertThat(ex.getMessage()).contains("代理分配冲突");
                    });
        } finally {
            TenantContext.clear();
        }
    }

    private static IpProxy idleProxy() {
        IpProxy row = new IpProxy();
        row.setId(10L);
        row.setHost("geo.iproyal.com");
        row.setPort(12321);
        row.setProtocol(ProxyEndpoint.PROTOCOL_SOCKS5);
        row.setUsername("user1");
        row.setPassword("pass1_country-in_session-Abc12345_lifetime-1h");
        row.setRegion("印度");
        row.setStatus(IpProxyStatus.IDLE.code());
        return row;
    }
}
