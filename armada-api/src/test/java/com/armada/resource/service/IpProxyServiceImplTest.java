package com.armada.resource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.country.service.CountryService;
import com.armada.resource.check.IpProxyCheckRequest;
import com.armada.resource.check.IpProxyCheckResult;
import com.armada.resource.check.IpProxyCheckTiming;
import com.armada.resource.check.IpProxyDetector;
import com.armada.resource.converter.IpProxyConverter;
import com.armada.resource.mapper.IpProxyBindTarget;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.dto.IpProxyImportDTO;
import com.armada.resource.model.dto.IpProxyQuery;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.vo.IpProxyCheckResultVO;
import com.armada.resource.model.vo.IpProxyImportResultVO;
import com.armada.resource.model.vo.IpProxyImportSampleCheckVO;
import com.armada.resource.service.impl.IpProxyServiceImpl;
import com.armada.platform.proxy.ProxyEndpoint;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * IP 代理导入业务单测(mock mapper/converter,验导入统计语义)。
 */
@ExtendWith(MockitoExtension.class)
class IpProxyServiceImplTest {

    @Mock
    private IpProxyMapper mapper;

    @Mock
    private IpProxyConverter converter;

    @Mock
    private CountryService countryService;

    @Mock
    private IpProxyDetector detector;

    @Mock
    private Executor ipProxyCheckExecutor;

    @InjectMocks
    private IpProxyServiceImpl service;

    /** 构造合法的导入 DTO(协议=1/HTTP,来源非空)。 */
    private IpProxyImportDTO dto(String text) {
        when(countryService.resolveIpRegion("中国")).thenReturn("中国");
        return new IpProxyImportDTO("中国", 1, "供应商A", text);
    }

    /** 默认让手动抽样检测通过。 */
    private void detectorChecksSucceed() {
        when(detector.check(any())).thenAnswer(invocation -> {
            IpProxyCheckRequest request = invocation.getArgument(0, IpProxyCheckRequest.class);
            return IpProxyCheckResult.success(
                    request.id(),
                    "103.10.10.10",
                    "IN",
                    "Mumbai",
                    "Example ISP",
                    null,
                    null,
                    400,
                    IpProxyCheckTiming.zero(),
                    1_719_800_000_000L);
        });
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
    void listRegions_returnsDistinctRegionsWithMixedFirst() {
        when(mapper.selectDistinctRegions("混合（不限国家）"))
                .thenReturn(List.of("混合（不限国家）", "印度", "马来西亚"));

        List<String> regions = service.listRegions();

        assertThat(regions).containsExactly("混合（不限国家）", "印度", "马来西亚");
        verify(mapper).selectDistinctRegions("混合（不限国家）");
    }

    @Test
    void list_resolvesCountryValueToLegacyRegionBeforeMapper() {
        IpProxyQuery query = new IpProxyQuery();
        query.setCountryValue("IN");
        when(countryService.resolveIpRegion("IN")).thenReturn("印度");
        when(mapper.countPage(query)).thenReturn(0L);

        service.list(query);

        assertThat(query.getRegion()).isEqualTo("印度");
        verify(mapper).countPage(query);
    }

    @Test
    void importProxies_resolvesCountryValueBeforePersistingRegion() {
        when(countryService.resolveIpRegion("IN")).thenReturn("印度");
        when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);

        IpProxyImportResultVO result = service.importProxies(
                new IpProxyImportDTO(null, 1, "供应商A", "1.1.1.1:8080:user1:pass1", "IN"));

        assertThat(result.insertedRows()).isEqualTo(1);
        ArgumentCaptor<IpProxy> proxyCaptor = ArgumentCaptor.forClass(IpProxy.class);
        verify(mapper).insert(proxyCaptor.capture());
        assertThat(proxyCaptor.getValue().getRegion()).isEqualTo("印度");
    }

    @Test
    void importProxies_persistsSelectedAllocationMode() throws Exception {
        when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);
        IpProxyImportDTO dto = IpProxyImportDTO.class
                .getConstructor(String.class, Integer.class, String.class, String.class, String.class, String.class)
                .newInstance(null, 1, "供应商A", "1.1.1.1:8080:user1:pass1", null, "mixed");

        service.importProxies(dto);

        ArgumentCaptor<IpProxy> proxyCaptor = ArgumentCaptor.forClass(IpProxy.class);
        verify(mapper).insert(proxyCaptor.capture());
        assertThat(proxyCaptor.getValue().getAllocationMode()).isEqualTo("mixed");
        assertThat(proxyCaptor.getValue().getRegion()).isEqualTo("混合（不限国家）");
        verify(countryService, never()).resolveIpRegion(any());
    }

    @Test
    void sampleCheckImport_failureReturnsFailedResultWithoutInsert() {
        when(countryService.resolveIpRegion("US")).thenReturn("美国");
        when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);
        when(detector.check(any()))
                .thenReturn(IpProxyCheckResult.failed(null, "代理连接超时", 1_719_800_000_000L));

        IpProxyImportSampleCheckVO result = service.sampleCheckImport(new IpProxyImportDTO(
                null,
                1,
                "供应商A",
                "1.1.1.1:8080:user1:pass1",
                "US"));

        assertThat(result.passed()).isFalse();
        assertThat(result.sampleSize()).isEqualTo(1);
        assertThat(result.errors()).containsExactly("第 1 行：代理连接超时");
        assertThat(result.samples()).hasSize(1);
        assertThat(result.samples().get(0).lineNo()).isEqualTo(1);
        assertThat(result.samples().get(0).host()).isEqualTo("1.1.1.1");
        assertThat(result.samples().get(0).port()).isEqualTo(8080);
        assertThat(result.samples().get(0).passed()).isFalse();
        verify(mapper, never()).insert(any());
    }

    @Test
    void sampleCheckImport_successChecksAtMostFiveRandomCandidates() {
        when(countryService.resolveIpRegion("US")).thenReturn("美国");
        when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);
        detectorChecksSucceed();

        IpProxyImportSampleCheckVO result = service.sampleCheckImport(new IpProxyImportDTO(
                null,
                1,
                "供应商A",
                """
                1.1.1.1:8001:user1:pass1
                1.1.1.2:8002:user2:pass2
                1.1.1.3:8003:user3:pass3
                1.1.1.4:8004:user4:pass4
                1.1.1.5:8005:user5:pass5
                1.1.1.6:8006:user6:pass6
                """,
                "US"));

        assertThat(result.passed()).isTrue();
        assertThat(result.sampleSize()).isEqualTo(5);
        assertThat(result.samples()).hasSize(5);
        assertThat(result.samples()).allSatisfy(sample -> assertThat(sample.passed()).isTrue());
        assertThat(result.errors()).isEmpty();
        verify(detector, times(5)).check(any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void importProxies_importsIdleRowsWithoutCallingDetectorOrWritingDetectionFields() {
        when(countryService.resolveIpRegion("US")).thenReturn("美国");
        when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);

        IpProxyImportResultVO result = service.importProxies(
                new IpProxyImportDTO(null, 1, "供应商A", "1.1.1.1:8080:user1:pass1", "US"));

        assertThat(result.insertedRows()).isEqualTo(1);
        ArgumentCaptor<IpProxy> insertCaptor = ArgumentCaptor.forClass(IpProxy.class);
        verify(mapper).insert(insertCaptor.capture());
        IpProxy inserted = insertCaptor.getValue();
        assertThat(inserted.getAllocationMode()).isEqualTo("smart");
        assertThat(inserted.getRegion()).isEqualTo("美国");
        assertThat(inserted.getStatus()).isEqualTo(IpProxyStatus.IDLE.code());
        assertThat(inserted.getDetectedCountryCode()).isNull();
        assertThat(inserted.getOutboundIp()).isNull();
        assertThat(inserted.getDetectedLocation()).isNull();
        assertThat(inserted.getDetectedIsp()).isNull();
        assertThat(inserted.getCheckFailCount()).isZero();
        assertThat(inserted.getCheckStatus()).isNull();
        assertThat(inserted.getWhatsappCheckStatus()).isNull();
        assertThat(inserted.getWhatsappHttpStatus()).isNull();
        assertThat(inserted.getWhatsappCheckError()).isNull();
        assertThat(inserted.getLastCheckError()).isNull();
        assertThat(inserted.getLastSampleCheckAt()).isNull();
        verify(detector, never()).check(any());
        verify(ipProxyCheckExecutor, never()).execute(any(Runnable.class));
        verify(mapper, never()).updateDetectionResult(any(), anyInt());
    }

    @Test
    void checkProxy_detectsActiveRowUpdatesDatabaseAndReturnsCurrentResult() {
        IpProxy row = idleProxy();
        row.setRegion("美国");
        IpProxy updated = idleProxy();
        updated.setRegion("美国");
        updated.setDetectedCountryCode("IN");
        updated.setOutboundIp("103.10.10.10");
        updated.setWhatsappHttpStatus(400);
        when(mapper.selectActiveById(10L)).thenReturn(row, updated);
        when(detector.check(any())).thenReturn(IpProxyCheckResult.success(
                10L,
                "103.10.10.10",
                "IN",
                "Mumbai",
                "Example ISP",
                null,
                null,
                400,
                IpProxyCheckTiming.zero(),
                1_719_800_000_000L));
        when(mapper.updateDetectionResult(any(), eq(IpProxyStatus.IN_USE.code()))).thenReturn(1);

        IpProxyCheckResultVO result = service.checkProxy(10L);

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.checkStatus()).isEqualTo("success");
        assertThat(result.connectionStatus()).isEqualTo("空闲");
        assertThat(result.countryCode()).isEqualTo("IN");
        assertThat(result.region()).isEqualTo("美国");
        assertThat(result.outboundIp()).isEqualTo("103.10.10.10");
        assertThat(result.whatsappStatus()).isEqualTo("HTTP 400");
        ArgumentCaptor<IpProxy> updateCaptor = ArgumentCaptor.forClass(IpProxy.class);
        verify(mapper).updateDetectionResult(updateCaptor.capture(), eq(IpProxyStatus.IN_USE.code()));
        assertThat(updateCaptor.getValue().getRegion()).isEqualTo("美国");
    }

    @Test
    void checkProxy_whenInUseDetectionSucceeds_returnsCurrentInUseStatus() {
        IpProxy inUse = idleProxy();
        inUse.setStatus(IpProxyStatus.IN_USE.code());
        inUse.setBoundAccountId(100L);
        IpProxy updated = idleProxy();
        updated.setStatus(IpProxyStatus.IN_USE.code());
        updated.setBoundAccountId(100L);
        updated.setRegion("印度");
        updated.setDetectedCountryCode("IN");
        updated.setOutboundIp("103.10.10.10");
        when(mapper.selectActiveById(10L)).thenReturn(inUse, updated);
        when(detector.check(any())).thenReturn(IpProxyCheckResult.success(
                10L,
                "103.10.10.10",
                "IN",
                "Mumbai",
                "Example ISP",
                null,
                null,
                400,
                IpProxyCheckTiming.zero(),
                1_719_800_000_000L));
        when(mapper.updateDetectionResult(any(), eq(IpProxyStatus.IN_USE.code()))).thenReturn(1);

        IpProxyCheckResultVO result = service.checkProxy(10L);

        assertThat(result.checkStatus()).isEqualTo("success");
        assertThat(result.connectionStatus()).isEqualTo("使用中");
        verify(mapper).updateDetectionResult(any(), eq(IpProxyStatus.IN_USE.code()));
    }

    @Test
    void checkProxy_whenInUseDetectionFails_returnsCurrentInUseStatus() {
        IpProxy inUse = idleProxy();
        inUse.setStatus(IpProxyStatus.IN_USE.code());
        inUse.setBoundAccountId(100L);
        IpProxy updated = idleProxy();
        updated.setStatus(IpProxyStatus.IN_USE.code());
        updated.setBoundAccountId(100L);
        updated.setLastCheckError("代理连接超时");
        when(mapper.selectActiveById(10L)).thenReturn(inUse, updated);
        when(detector.check(any())).thenReturn(IpProxyCheckResult.failed(10L, "代理连接超时", 1_719_800_000_000L));
        when(mapper.updateDetectionResult(any(), eq(IpProxyStatus.IN_USE.code()))).thenReturn(1);

        IpProxyCheckResultVO result = service.checkProxy(10L);

        assertThat(result.checkStatus()).isEqualTo("failed");
        assertThat(result.connectionStatus()).isEqualTo("使用中");
        verify(mapper).updateDetectionResult(any(), eq(IpProxyStatus.IN_USE.code()));
    }

    @Test
    void checkProxy_updateAffectedRowsMismatchThrowsConflict() {
        IpProxy row = idleProxy();
        when(mapper.selectActiveById(10L)).thenReturn(row);
        when(detector.check(any())).thenReturn(IpProxyCheckResult.failed(10L, "代理连接超时", 1_719_800_000_000L));
        when(mapper.updateDetectionResult(any(), eq(IpProxyStatus.IN_USE.code()))).thenReturn(0);

        assertThatThrownBy(() -> service.checkProxy(10L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code());
                    assertThat(ex.getMessage()).contains("代理检测结果更新冲突");
                });
    }

    @Test
    void checkProxies_rejectsInvalidInputBeforeMapperAndDetector() {
        assertThatThrownBy(() -> service.checkProxies(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("检测 IP 列表不能为空");

        assertThatThrownBy(() -> service.checkProxies(List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("检测 IP 列表不能为空");

        assertThatThrownBy(() -> service.checkProxies(java.util.stream.LongStream.rangeClosed(1, 21).boxed().toList()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("一次最多检测 20 个 IP");

        assertThatThrownBy(() -> service.checkProxies(java.util.Arrays.asList(10L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("代理 ID 不能为空");

        assertThatThrownBy(() -> service.checkProxies(List.of(10L, 10L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("代理 ID 不能重复: 10");

        verifyNoInteractions(detector);
    }

    @Test
    void checkProxies_missingIdDoesNotCallDetector() {
        when(mapper.selectActiveByIds(List.of(10L, 11L))).thenReturn(List.of(idleProxy(10L, "proxy-a.internal")));

        assertThatThrownBy(() -> service.checkProxies(List.of(10L, 11L)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND.code());
                    assertThat(ex.getMessage()).contains("代理不存在或已删除: 11");
                });

        verifyNoInteractions(detector);
    }

    @Test
    void publicDetectionEntrypointsDoNotOpenLongTransactions() throws Exception {
        assertThat(transactionalAnnotation("importProxies", IpProxyImportDTO.class)).isNull();
        assertThat(transactionalAnnotation("checkProxy", Long.class)).isNull();
        assertThat(transactionalAnnotation("checkProxies", List.class)).isNull();
    }

    @Test
    void importProxies_legacyRegionStillResolvesThroughCountryService() {
        when(countryService.resolveIpRegion("印度")).thenReturn("印度");
        when(mapper.countActiveByFullTuple(anyString(), anyInt(), anyString(), anyString())).thenReturn(0L);

        service.importProxies(new IpProxyImportDTO("印度", 1, "供应商A", "1.1.1.1:8080:user1:pass1"));

        ArgumentCaptor<IpProxy> proxyCaptor = ArgumentCaptor.forClass(IpProxy.class);
        verify(mapper).insert(proxyCaptor.capture());
        assertThat(proxyCaptor.getValue().getRegion()).isEqualTo("印度");
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
            when(mapper.selectOneIdleByRegionPriorityForUpdate(
                    1L,
                    IpProxyStatus.IDLE.code(),
                    "印度",
                    "混合（不限国家）",
                    List.of())).thenReturn(row);
            when(mapper.markUsingAndBind(
                    eq(10L),
                    eq(100L),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(1);

            IpProxyAllocation allocation = service.allocateOnlineEndpoint(
                    new IpProxyAllocationRequest(100L, "印度"));

            assertThat(allocation.proxyId()).isEqualTo(10L);
            assertThat(allocation.endpoint().protocolCode()).isEqualTo(ProxyEndpoint.PROTOCOL_SOCKS5);
            assertThat(allocation.endpoint().host()).isEqualTo("geo.iproyal.com");
            assertThat(allocation.endpoint().port()).isEqualTo(12321);
            assertThat(allocation.endpoint().credentials().username()).isEqualTo("user1");
            assertThat(allocation.endpoint().credentials().password())
                    .isEqualTo("pass1_country-in_session-Abc12345_lifetime-1h");
            assertThat(allocation.endpoint().country()).isEqualTo("印度");
            assertThat(allocation.proxySource()).isEqualTo("iproyal");

            InOrder inOrder = org.mockito.Mockito.inOrder(mapper);
            inOrder.verify(mapper).releaseByAccount(
                    eq(100L), eq(IpProxyStatus.IDLE.code()), eq(IpProxyStatus.IN_USE.code()), anyLong());
            inOrder.verify(mapper).selectOneIdleByRegionPriorityForUpdate(
                    1L,
                    IpProxyStatus.IDLE.code(),
                    "印度",
                    "混合（不限国家）",
                    List.of());
            inOrder.verify(mapper).markUsingAndBind(
                    eq(10L), eq(100L), eq(IpProxyStatus.IDLE.code()), eq(IpProxyStatus.IN_USE.code()), anyLong());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void allocateOnlineEndpoints_releasesOldBindingsLocksIdleRowsAndMarksUsingInBatch() {
        TenantContext.set(1L);
        try {
            IpProxy proxyA = idleProxy(10L, "proxy-a.internal");
            IpProxy proxyB = idleProxy(11L, "proxy-b.internal");
            List<IpProxyAllocationRequest> requests = List.of(
                    new IpProxyAllocationRequest(100L, "印度"),
                    new IpProxyAllocationRequest(101L, "马来西亚"));
            when(mapper.releaseByAccounts(
                    eq(List.of(100L, 101L)),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(2);
            when(mapper.selectOneIdleByRegionPriorityForUpdate(
                    1L,
                    IpProxyStatus.IDLE.code(),
                    "印度",
                    "混合（不限国家）",
                    List.of())).thenReturn(proxyA);
            when(mapper.selectOneIdleByRegionPriorityForUpdate(
                    1L,
                    IpProxyStatus.IDLE.code(),
                    "马来西亚",
                    "混合（不限国家）",
                    List.of(10L))).thenReturn(proxyB);
            when(mapper.markUsingAndBindBatch(
                    any(),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(2);

            List<IpProxyAccountAllocation> allocations = service.allocateOnlineEndpoints(requests);

            assertThat(allocations).hasSize(2);
            assertThat(allocations).extracting(IpProxyAccountAllocation::accountId)
                    .containsExactly(100L, 101L);
            assertThat(allocations).extracting(IpProxyAccountAllocation::proxyId)
                    .containsExactly(10L, 11L);
            assertThat(allocations.get(0).endpoint().host()).isEqualTo("proxy-a.internal");
            assertThat(allocations.get(1).endpoint().host()).isEqualTo("proxy-b.internal");
            assertThat(allocations).extracting(IpProxyAccountAllocation::proxySource)
                    .containsExactly("iproyal", "iproyal");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<IpProxyBindTarget>> bindCaptor = ArgumentCaptor.forClass(List.class);
            verify(mapper).markUsingAndBindBatch(
                    bindCaptor.capture(),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong());
            assertThat(bindCaptor.getValue()).extracting(IpProxyBindTarget::proxyId)
                    .containsExactly(10L, 11L);
            assertThat(bindCaptor.getValue()).extracting(IpProxyBindTarget::accountId)
                    .containsExactly(100L, 101L);

            InOrder inOrder = org.mockito.Mockito.inOrder(mapper);
            inOrder.verify(mapper).releaseByAccounts(
                    eq(List.of(100L, 101L)), eq(IpProxyStatus.IDLE.code()), eq(IpProxyStatus.IN_USE.code()), anyLong());
            inOrder.verify(mapper).selectOneIdleByRegionPriorityForUpdate(
                    1L, IpProxyStatus.IDLE.code(), "印度", "混合（不限国家）", List.of());
            inOrder.verify(mapper).selectOneIdleByRegionPriorityForUpdate(
                    1L, IpProxyStatus.IDLE.code(), "马来西亚", "混合（不限国家）", List.of(10L));
            inOrder.verify(mapper).markUsingAndBindBatch(
                    any(), eq(IpProxyStatus.IDLE.code()), eq(IpProxyStatus.IN_USE.code()), anyLong());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void allocateOnlineEndpointsExcludingProxyIds_releasesOldBindingsAndLocksIdleRowsExcludingDeletedIps() {
        TenantContext.set(1L);
        try {
            List<IpProxyAllocationRequest> requests = List.of(
                    new IpProxyAllocationRequest(100L, "印度"),
                    new IpProxyAllocationRequest(101L, "马来西亚"));
            List<Long> excludedProxyIds = List.of(10L, 11L);
            IpProxy proxyA = idleProxy(20L, "proxy-a.internal");
            IpProxy proxyB = idleProxy(21L, "proxy-b.internal");
            when(mapper.releaseByAccounts(
                    eq(List.of(100L, 101L)),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(2);
            when(mapper.selectOneIdleByRegionPriorityForUpdate(
                    1L,
                    IpProxyStatus.IDLE.code(),
                    "印度",
                    "混合（不限国家）",
                    excludedProxyIds)).thenReturn(proxyA);
            when(mapper.selectOneIdleByRegionPriorityForUpdate(
                    1L,
                    IpProxyStatus.IDLE.code(),
                    "马来西亚",
                    "混合（不限国家）",
                    List.of(10L, 11L, 20L))).thenReturn(proxyB);
            when(mapper.markUsingAndBindBatch(
                    any(),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(2);

            List<IpProxyAccountAllocation> allocations =
                    service.allocateOnlineEndpointsExcludingProxyIds(requests, excludedProxyIds);

            assertThat(allocations).extracting(IpProxyAccountAllocation::proxyId)
                    .containsExactly(20L, 21L);
            InOrder inOrder = org.mockito.Mockito.inOrder(mapper);
            inOrder.verify(mapper).releaseByAccounts(
                    eq(List.of(100L, 101L)), eq(IpProxyStatus.IDLE.code()), eq(IpProxyStatus.IN_USE.code()), anyLong());
            inOrder.verify(mapper).selectOneIdleByRegionPriorityForUpdate(
                    1L, IpProxyStatus.IDLE.code(), "印度", "混合（不限国家）", excludedProxyIds);
            inOrder.verify(mapper).selectOneIdleByRegionPriorityForUpdate(
                    1L, IpProxyStatus.IDLE.code(), "马来西亚", "混合（不限国家）", List.of(10L, 11L, 20L));
            inOrder.verify(mapper).markUsingAndBindBatch(
                    any(), eq(IpProxyStatus.IDLE.code()), eq(IpProxyStatus.IN_USE.code()), anyLong());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void findBoundAccountIdsByProxyIds_delegatesMapperWithUsingStatus() {
        List<Long> ids = List.of(10L, 11L);
        when(mapper.selectBoundAccountIdsByProxyIds(ids, IpProxyStatus.IN_USE.code()))
                .thenReturn(List.of(100L, 101L));

        List<Long> result = service.findBoundAccountIdsByProxyIds(ids);

        assertThat(result).containsExactly(100L, 101L);
        verify(mapper).selectBoundAccountIdsByProxyIds(ids, IpProxyStatus.IN_USE.code());
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

        assertThatThrownBy(() -> service.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度")))
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
            when(mapper.selectOneIdleByRegionPriorityForUpdate(
                    1L,
                    IpProxyStatus.IDLE.code(),
                    "印度",
                    "混合（不限国家）",
                    List.of())).thenReturn(null);

            assertThatThrownBy(() -> service.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度")))
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
            when(mapper.selectOneIdleByRegionPriorityForUpdate(
                    1L,
                    IpProxyStatus.IDLE.code(),
                    "印度",
                    "混合（不限国家）",
                    List.of())).thenReturn(idleProxy());
            when(mapper.markUsingAndBind(
                    eq(10L),
                    eq(100L),
                    eq(IpProxyStatus.IDLE.code()),
                    eq(IpProxyStatus.IN_USE.code()),
                    anyLong())).thenReturn(0);

            assertThatThrownBy(() -> service.allocateOnlineEndpoint(new IpProxyAllocationRequest(100L, "印度")))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT.code());
                        assertThat(ex.getMessage()).contains("代理分配冲突");
                    });
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void releaseOnlineAllocation_validIds_delegatesPreciseMapperRelease() {
        when(mapper.releaseOnlineAllocation(
                eq(100L),
                eq(10L),
                eq(IpProxyStatus.IDLE.code()),
                eq(IpProxyStatus.IN_USE.code()),
                anyLong())).thenReturn(1);

        service.releaseOnlineAllocation(100L, 10L);

        verify(mapper).releaseOnlineAllocation(
                eq(100L),
                eq(10L),
                eq(IpProxyStatus.IDLE.code()),
                eq(IpProxyStatus.IN_USE.code()),
                anyLong());
    }

    @Test
    void releaseOnlineAllocations_validItems_delegatesPreciseBatchRelease() {
        List<IpProxyAccountAllocation> allocations = List.of(
                new IpProxyAccountAllocation(100L, 10L, null, null),
                new IpProxyAccountAllocation(101L, 11L, null, null));
        when(mapper.releaseOnlineAllocations(
                any(),
                eq(IpProxyStatus.IDLE.code()),
                eq(IpProxyStatus.IN_USE.code()),
                anyLong())).thenReturn(2);

        service.releaseOnlineAllocations(allocations);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IpProxyBindTarget>> targetCaptor = ArgumentCaptor.forClass(List.class);
        verify(mapper).releaseOnlineAllocations(
                targetCaptor.capture(),
                eq(IpProxyStatus.IDLE.code()),
                eq(IpProxyStatus.IN_USE.code()),
                anyLong());
        assertThat(targetCaptor.getValue()).extracting(IpProxyBindTarget::proxyId)
                .containsExactly(10L, 11L);
        assertThat(targetCaptor.getValue()).extracting(IpProxyBindTarget::accountId)
                .containsExactly(100L, 101L);
    }

    @Test
    void releaseOnlineAllocation_nullProxyId_throwsValidationBeforeMapper() {
        assertThatThrownBy(() -> service.releaseOnlineAllocation(100L, null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(ex.getMessage()).contains("代理 ID 不能为空");
                });

        verify(mapper, never()).releaseOnlineAllocation(any(), any(), anyInt(), anyInt(), anyLong());
    }

    private static IpProxy idleProxy() {
        return idleProxy(10L, "geo.iproyal.com");
    }

    private static IpProxy idleProxy(Long id, String host) {
        IpProxy row = new IpProxy();
        row.setId(id);
        row.setHost(host);
        row.setPort(12321);
        row.setProtocol(ProxyEndpoint.PROTOCOL_SOCKS5);
        row.setUsername("user1");
        row.setPassword("pass1_country-in_session-Abc12345_lifetime-1h");
        row.setRegion("印度");
        row.setStatus(IpProxyStatus.IDLE.code());
        row.setSource("iproyal");
        return row;
    }

    private static Transactional transactionalAnnotation(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = IpProxyServiceImpl.class.getMethod(methodName, parameterTypes);
        return method.getAnnotation(Transactional.class);
    }
}
