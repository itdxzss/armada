package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountWsPhoneExportDTO;
import com.armada.account.model.vo.AccountWsPhoneExportFile;
import com.armada.account.model.vo.AccountWsPhoneExportRow;
import com.armada.shared.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountWsPhoneExportServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-07-14T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Mock
    private AccountMapper accountMapper;

    private AccountWsPhoneExportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AccountWsPhoneExportServiceImpl(accountMapper, FIXED_CLOCK);
    }

    @Test
    void exportCleansDeduplicatesAndCountsActualLines() {
        when(accountMapper.selectWsPhonesByIds(List.of(3L, 1L, 2L)))
                .thenReturn(List.of(
                        row(1L, "+60 (12) 345-6789"),
                        row(2L, "60-12-345-6789"),
                        row(3L, "001 234 ABC")));

        AccountWsPhoneExportFile file = service.export(
                new AccountWsPhoneExportDTO(Arrays.asList(3L, null, 1L, 3L, 2L), "马来西亚客户组"));

        assertThat(file.filename()).isEqualTo("马来西亚客户组_2026-07-14.txt");
        assertThat(new String(file.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("60123456789\n001234");
        assertThat(file.exportedCount()).isEqualTo(2);
        verify(accountMapper).selectWsPhonesByIds(List.of(3L, 1L, 2L));
    }

    @Test
    void exportSkipsNullEmptyAndNonDigitPhones() {
        when(accountMapper.selectWsPhonesByIds(List.of(1L, 2L, 3L)))
                .thenReturn(Arrays.asList(row(1L, null), row(2L, "  +()-  "), row(3L, "")));

        assertThatThrownBy(() -> service.export(
                new AccountWsPhoneExportDTO(List.of(1L, 2L, 3L), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前所选账号中没有可导出的有效WS号码。");
    }

    @Test
    void exportUsesFallbackAndSanitizesUnsafeFilenameCharacters() {
        when(accountMapper.selectWsPhonesByIds(List.of(1L)))
                .thenReturn(List.of(row(1L, "8613800138000")));

        AccountWsPhoneExportFile fallback = service.export(
                new AccountWsPhoneExportDTO(List.of(1L), "   "));
        AccountWsPhoneExportFile safe = service.export(
                new AccountWsPhoneExportDTO(List.of(1L), " 马来/西亚:*?组. "));

        assertThat(fallback.filename()).isEqualTo("全部WS号_2026-07-14.txt");
        assertThat(safe.filename()).isEqualTo("马来_西亚___组_2026-07-14.txt");
    }

    @Test
    void exportQueriesAtMostFiveHundredIdsPerChunk() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 501; id++) {
            ids.add(id);
        }
        when(accountMapper.selectWsPhonesByIds(anyList()))
                .thenAnswer(invocation -> {
                    List<Long> chunk = invocation.getArgument(0);
                    return chunk.contains(501L) ? List.of(row(501L, "8613800138000")) : List.of();
                });

        AccountWsPhoneExportFile file = service.export(new AccountWsPhoneExportDTO(ids, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> chunks = ArgumentCaptor.forClass(List.class);
        verify(accountMapper, org.mockito.Mockito.times(2))
                .selectWsPhonesByIds(chunks.capture());
        assertThat(chunks.getAllValues()).extracting(List::size).containsExactly(500, 1);
        assertThat(file.exportedCount()).isEqualTo(1);
    }

    @Test
    void exportRejectsEmptyAndMoreThanTwoThousandUniqueIds() {
        assertThatThrownBy(() -> service.export(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号 ID 列表不能为空");
        assertThatThrownBy(() -> service.export(new AccountWsPhoneExportDTO(Arrays.asList(null, null), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号 ID 列表不能为空");

        List<Long> tooMany = new ArrayList<>();
        for (long id = 1; id <= 2001; id++) {
            tooMany.add(id);
        }
        assertThatThrownBy(() -> service.export(new AccountWsPhoneExportDTO(tooMany, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("单次最多导出 2000 个账号");
        verify(accountMapper, never()).selectWsPhonesByIds(anyList());
    }

    @Test
    void exportAllowsExactlyTwoThousandUniqueIds() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 2000; id++) {
            ids.add(id);
        }
        when(accountMapper.selectWsPhonesByIds(anyList()))
                .thenAnswer(invocation -> {
                    List<Long> chunk = invocation.getArgument(0);
                    return chunk.contains(2000L) ? List.of(row(2000L, "8613800138000")) : List.of();
                });

        AccountWsPhoneExportFile file = service.export(new AccountWsPhoneExportDTO(ids, null));

        assertThat(file.exportedCount()).isEqualTo(1);
        verify(accountMapper, org.mockito.Mockito.times(4))
                .selectWsPhonesByIds(anyList());
    }

    @Test
    void exportConvertsDataAccessFailureToStableBusinessError() {
        when(accountMapper.selectWsPhonesByIds(List.of(1L)))
                .thenThrow(new IllegalStateException("database details"));

        assertThatThrownBy(() -> service.export(new AccountWsPhoneExportDTO(List.of(1L), null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(50001);
                    assertThat(ex.getMessage()).isEqualTo("导出失败，请重新操作。");
                });
    }

    private static AccountWsPhoneExportRow row(Long id, String phone) {
        AccountWsPhoneExportRow row = new AccountWsPhoneExportRow();
        row.setId(id);
        row.setWsPhone(phone);
        return row;
    }
}
