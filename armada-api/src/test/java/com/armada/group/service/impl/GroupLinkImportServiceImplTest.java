package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupLinkImportBatchMapper;
import com.armada.group.mapper.GroupLinkImportDetailMapper;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.GroupLinkImportResult;
import com.armada.group.model.dto.GroupLinkImportDTO;
import com.armada.group.model.dto.GroupLinkImportDetailQuery;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkImportBatch;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupLinkImportDetailVO;
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import com.armada.group.model.vo.GroupLinkImportResultVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * GroupLinkImportServiceImpl 业务规则单测(mock mapper,验四态 + 校验逻辑;真库另由 DbTest 覆盖)。
 */
@ExtendWith(MockitoExtension.class)
class GroupLinkImportServiceImplTest {

    @Mock
    private GroupLinkLabelMapper labelMapper;

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupLinkImportBatchMapper importBatchMapper;

    @Mock
    private GroupLinkImportDetailMapper detailMapper;

    @Mock
    private GroupConverter converter;

    @InjectMocks
    private GroupLinkImportServiceImpl service;

    /** 辅助:构造一个有效的 label stub */
    private void stubValidLabel(Long labelId) {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setId(labelId);
        when(labelMapper.selectById(labelId)).thenReturn(label);
    }

    /** 辅助:让 importBatchMapper.insert 填充自增 id */
    private void stubBatchInsert(Long batchId) {
        doAnswer(inv -> {
            GroupLinkImportBatch b = inv.getArgument(0);
            b.setId(batchId);
            return 1;
        }).when(importBatchMapper).insert(any());
    }

    /** 辅助:让 groupLinkMapper.insert 填充自增 id */
    private void stubLinkInsert(Long linkId) {
        doAnswer(inv -> {
            GroupLink g = inv.getArgument(0);
            g.setId(linkId);
            return 1;
        }).when(groupLinkMapper).insert(any());
    }

    // ---- 四态测试 ----

    @Test
    void newUrl_inserts_andDetailSuccess() {
        stubValidLabel(1L);
        stubBatchInsert(10L);
        stubLinkInsert(100L);
        when(groupLinkMapper.selectAnyByUrl(anyString())).thenReturn(null);

        GroupLinkImportResultVO result = service.importLinks(
                new GroupLinkImportDTO(1L, "batch1", null,
                        List.of("https://chat.whatsapp.com/AbcDef")));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.exists()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.batchId()).isEqualTo(10L);
        verify(groupLinkMapper).insert(any());
        verify(detailMapper).batchInsert(any());
    }

    @Test
    void existingActiveUrl_reportsExists_andDoesNotTouchLink() {
        stubValidLabel(2L);
        stubBatchInsert(20L);
        GroupLink existing = new GroupLink();
        existing.setId(200L);
        existing.setDeletedAt(null);  // 活跃链接
        when(groupLinkMapper.selectAnyByUrl(anyString())).thenReturn(existing);

        GroupLinkImportResultVO result = service.importLinks(
                new GroupLinkImportDTO(2L, "batch2", null,
                        List.of("https://chat.whatsapp.com/AbcDef")));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.exists()).isEqualTo(1);
        assertThat(result.inserted()).isEqualTo(0);
        // 已存在的活跃链接:既不插入,也不改归属(不调 adoptToLabel)
        verify(groupLinkMapper, never()).adoptToLabel(anyLong(), anyLong(), anyLong(), any());
        verify(groupLinkMapper, never()).insert(any());
    }

    @Test
    void existingSoftDeletedUrl_revives_asSuccess() {
        stubValidLabel(2L);
        stubBatchInsert(20L);
        GroupLink existing = new GroupLink();
        existing.setId(200L);
        existing.setDeletedAt(LocalDateTime.now());  // 软删链接
        when(groupLinkMapper.selectAnyByUrl(anyString())).thenReturn(existing);

        GroupLinkImportResultVO result = service.importLinks(
                new GroupLinkImportDTO(2L, "batch2", null,
                        List.of("https://chat.whatsapp.com/AbcDef")));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.inserted()).isEqualTo(1);  // 复活计入成功
        assertThat(result.exists()).isEqualTo(0);
        // 软删链接:复活并改归属本分组
        verify(groupLinkMapper).adoptToLabel(eq(200L), eq(2L), eq(20L), eq(null));
        verify(groupLinkMapper, never()).insert(any());
    }

    @Test
    void batchDuplicate_skipped_andDetailDuplicate() {
        stubValidLabel(3L);
        stubBatchInsert(30L);
        stubLinkInsert(300L);
        when(groupLinkMapper.selectAnyByUrl(anyString())).thenReturn(null);

        // 同一 url 出现两次
        GroupLinkImportResultVO result = service.importLinks(
                new GroupLinkImportDTO(3L, "batch3", null,
                        List.of("https://chat.whatsapp.com/SameCode",
                                "https://chat.whatsapp.com/SameCode")));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.inserted()).isEqualTo(1);  // 第一条 SUCCESS
        assertThat(result.duplicated()).isEqualTo(1); // 第二条 DUPLICATE
        // insert 只调用一次(去重后只有一条进 persist)
        verify(groupLinkMapper).insert(any());
    }

    @Test
    void badFormat_failed_andDetailFormatError() {
        stubValidLabel(4L);
        stubBatchInsert(40L);

        GroupLinkImportResultVO result = service.importLinks(
                new GroupLinkImportDTO(4L, "batch4", null,
                        List.of("not-a-whatsapp-link")));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("第 1 行");
        verify(groupLinkMapper, never()).insert(any());
    }

    @Test
    void counts_areWrittenBackToBatch() {
        stubValidLabel(5L);
        stubBatchInsert(50L);
        stubLinkInsert(500L);
        when(groupLinkMapper.selectAnyByUrl(anyString())).thenReturn(null);

        service.importLinks(new GroupLinkImportDTO(5L, "batch5", null,
                List.of("https://chat.whatsapp.com/Code1",
                        "bad-url",
                        "https://chat.whatsapp.com/Code1")));  // 重复

        // 验 updateCounts 被调用(统计已在 VO 中断言)
        verify(importBatchMapper).updateCounts(any());
    }

    @Test
    void blankBatchName_storedAsNull() {
        // 批次名称(来源文件/批次名称)非必填:用户留空(空白串)时,落库应为 null,而非空白字符串。
        stubValidLabel(7L);
        stubBatchInsert(70L);
        stubLinkInsert(700L);
        when(groupLinkMapper.selectAnyByUrl(anyString())).thenReturn(null);

        service.importLinks(new GroupLinkImportDTO(7L, "   ", null,
                List.of("https://chat.whatsapp.com/Blank")));

        ArgumentCaptor<GroupLinkImportBatch> captor = ArgumentCaptor.forClass(GroupLinkImportBatch.class);
        verify(importBatchMapper).insert(captor.capture());
        assertThat(captor.getValue().getBatchName()).isNull();
    }

    // ---- 校验失败 ----

    @Test
    void labelNotExists_throws() {
        when(labelMapper.selectById(anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.importLinks(
                new GroupLinkImportDTO(99L, "x", null, List.of("https://chat.whatsapp.com/X"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分组不存在");
        verify(importBatchMapper, never()).insert(any());
    }

    @Test
    void nullLabelId_throws() {
        assertThatThrownBy(() -> service.importLinks(
                new GroupLinkImportDTO(null, "x", null, List.of("https://chat.whatsapp.com/X"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分组不存在");
    }

    @Test
    void emptyLines_throws() {
        stubValidLabel(1L);

        assertThatThrownBy(() -> service.importLinks(
                new GroupLinkImportDTO(1L, "x", null, List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可为空");
        verify(importBatchMapper, never()).insert(any());
    }

    @Test
    void nullLines_throws() {
        stubValidLabel(1L);

        assertThatThrownBy(() -> service.importLinks(
                new GroupLinkImportDTO(1L, "x", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可为空");
    }

    // ---- listDetails 测试 ----

    @Test
    void listDetails_delegatesCountAndPage() {
        GroupLinkImportDetailQuery query = new GroupLinkImportDetailQuery();
        query.setPage(1);
        query.setPageSize(10);
        GroupLinkImportDetailVoRow voRow = new GroupLinkImportDetailVoRow();
        voRow.setLineNo(1);
        voRow.setResult(1);
        voRow.setCreatedAt(LocalDateTime.of(2024, 6, 1, 0, 0, 0));
        GroupLinkImportDetailVO detailVO = new GroupLinkImportDetailVO(1, null, null, null, 1, "成功", null, 1717200000000L);

        when(detailMapper.countByQuery(query)).thenReturn(1L);
        when(detailMapper.selectPage(query)).thenReturn(List.of(voRow));
        when(converter.toImportDetailVOList(any())).thenReturn(List.of(detailVO));

        PageResult<GroupLinkImportDetailVO> result = service.listDetails(query);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.list()).hasSize(1);
        assertThat(result.list().get(0).lineNo()).isEqualTo(1);
        verify(detailMapper).countByQuery(query);
        verify(detailMapper).selectPage(query);
        verify(converter).toImportDetailVOList(any());
    }

    @Test
    void listDetails_zeroTotal_skipsSelectPage() {
        GroupLinkImportDetailQuery query = new GroupLinkImportDetailQuery();
        when(detailMapper.countByQuery(query)).thenReturn(0L);

        PageResult<GroupLinkImportDetailVO> result = service.listDetails(query);

        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.list()).isEmpty();
        verify(detailMapper, never()).selectPage(any());
    }

    // ---- exportFailed 测试 ----

    @Test
    void exportFailed_mapsRowsToStringArrays() {
        GroupLinkImportDetailVoRow row = new GroupLinkImportDetailVoRow();
        row.setLineNo(3);
        row.setGroupName("群A");
        row.setRawUrl("chat.whatsapp.com/BadLink");
        row.setFailReason("格式错误");
        row.setResult(4);
        row.setCreatedAt(LocalDateTime.of(2024, 6, 1, 12, 0, 0));  // UTC 12:00 → Asia/Shanghai 20:00

        when(detailMapper.selectFailed(null, 10L)).thenReturn(List.of(row));

        List<String[]> rows = service.exportFailed(null, 10L);

        assertThat(rows).hasSize(1);
        String[] csvRow = rows.get(0);
        assertThat(csvRow[0]).isEqualTo("3");
        assertThat(csvRow[1]).isEqualTo("群A");
        assertThat(csvRow[2]).isEqualTo("chat.whatsapp.com/BadLink");
        assertThat(csvRow[3]).isEqualTo("格式错误");
        // UTC 2024-06-01 12:00:00 → Asia/Shanghai 2024-06-01 20:00:00
        assertThat(csvRow[4]).isEqualTo("2024-06-01 20:00:00");
    }

    @Test
    void exportFailed_nullFields_returnEmptyStrings() {
        GroupLinkImportDetailVoRow row = new GroupLinkImportDetailVoRow();
        row.setLineNo(1);
        row.setGroupName(null);
        row.setRawUrl(null);
        row.setFailReason(null);
        row.setResult(3);
        row.setCreatedAt(null);

        when(detailMapper.selectFailed(1L, null)).thenReturn(List.of(row));

        List<String[]> rows = service.exportFailed(1L, null);

        assertThat(rows).hasSize(1);
        String[] csvRow = rows.get(0);
        assertThat(csvRow[1]).isEmpty();   // groupName
        assertThat(csvRow[2]).isEmpty();   // rawUrl
        assertThat(csvRow[3]).isEmpty();   // failReason
        assertThat(csvRow[4]).isEmpty();   // time
    }

    @Test
    void exportFailed_emptyResult_returnsEmptyList() {
        when(detailMapper.selectFailed(99L, null)).thenReturn(List.of());

        List<String[]> rows = service.exportFailed(99L, null);

        assertThat(rows).isEmpty();
    }

    @Test
    void exportFailed_bothNull_throws() {
        assertThatThrownBy(() -> service.exportFailed(null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少提供一个");
    }
}
