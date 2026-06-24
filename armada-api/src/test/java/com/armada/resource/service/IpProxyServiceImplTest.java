package com.armada.resource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.resource.converter.IpProxyConverter;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.dto.IpProxyImportDTO;
import com.armada.resource.model.vo.IpProxyImportResultVO;
import com.armada.resource.service.impl.IpProxyServiceImpl;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}
