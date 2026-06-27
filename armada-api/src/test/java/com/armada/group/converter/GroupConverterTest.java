package com.armada.group.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.GroupLinkImportResult;
import com.armada.group.model.enums.GroupLinkImportFailReason;
import com.armada.group.model.enums.GroupLinkImportSuccessType;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.vo.GroupLinkImportDetailVO;
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import com.armada.group.model.vo.GroupLinkLabelVoRow;
import com.armada.group.model.vo.GroupLinkLabelVO;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.model.vo.GroupLinkVoRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** GroupConverter 单测:时间字段已是 epoch 毫秒,转换层直映。 */
class GroupConverterTest {

    private final GroupConverter converter = Mappers.getMapper(GroupConverter.class);

    /** 2024-06-01T00:00:00 按 UTC 解释 = 1717200000000 毫秒。 */
    private static final long EPOCH_2024_06_01_UTC = 1_717_200_000_000L;

    @Test
    void toLabelVO_epochMillis() {
        GroupLinkLabelVoRow row = new GroupLinkLabelVoRow();
        row.setId(1L);
        row.setName("印度");
        row.setRegion("印度");
        row.setRemark("r");
        row.setLinkCount(5L);
        row.setCreatedAt(EPOCH_2024_06_01_UTC);
        row.setUpdatedAt(EPOCH_2024_06_01_UTC);

        GroupLinkLabelVO vo = converter.toLabelVO(row);

        assertThat(vo.createdAt()).isEqualTo(EPOCH_2024_06_01_UTC);
        assertThat(vo.updatedAt()).isEqualTo(EPOCH_2024_06_01_UTC);
        assertThat(vo.linkCount()).isEqualTo(5L);
        assertThat(vo.name()).isEqualTo("印度");
    }

    @Test
    void toGroupLinkVO_epochMillis() {
        GroupLinkVoRow row = new GroupLinkVoRow();
        row.setId(10L);
        row.setUrl("https://chat.whatsapp.com/test");
        row.setGroupName("测试群");
        row.setSourceFileName("links.txt");
        row.setCreatedAt(EPOCH_2024_06_01_UTC);

        GroupLinkVO vo = converter.toGroupLinkVO(row);

        assertThat(vo.id()).isEqualTo(10L);
        assertThat(vo.url()).isEqualTo("https://chat.whatsapp.com/test");
        assertThat(vo.groupName()).isEqualTo("测试群");
        assertThat(vo.sourceFileName()).isEqualTo("links.txt");
        assertThat(vo.createdAt()).isEqualTo(EPOCH_2024_06_01_UTC);
    }

    @Test
    void toGroupLinkVO_nullTime_returnsNullEpoch() {
        GroupLinkVoRow row = new GroupLinkVoRow();
        row.setId(1L);
        row.setUrl("https://chat.whatsapp.com/abc");
        row.setCreatedAt(null);

        GroupLinkVO vo = converter.toGroupLinkVO(row);

        assertThat(vo.createdAt()).isNull();
    }

    @Test
    void toImportDetailVO_epochMillis() {
        GroupLinkImportDetailVoRow row = new GroupLinkImportDetailVoRow();
        row.setLineNo(3);
        row.setGroupName("测试群");
        row.setRawUrl("https://chat.whatsapp.com/AbcDef");
        row.setSourceFileName("links.txt");
        row.setResult(GroupLinkImportResult.SUCCESS.code());
        row.setSuccessType(GroupLinkImportSuccessType.ADOPTED.code());
        row.setExistingOrigin(GroupLinkOrigin.PULL_TASK.code());
        row.setCreatedAt(EPOCH_2024_06_01_UTC);

        GroupLinkImportDetailVO vo = converter.toImportDetailVO(row);

        assertThat(vo.lineNo()).isEqualTo(3);
        assertThat(vo.groupName()).isEqualTo("测试群");
        assertThat(vo.rawUrl()).isEqualTo("https://chat.whatsapp.com/AbcDef");
        assertThat(vo.sourceFileName()).isEqualTo("links.txt");
        assertThat(vo.result()).isEqualTo(GroupLinkImportResult.SUCCESS.code());
        assertThat(vo.resultLabel()).isEqualTo("成功");  // 后端按 result 码算好中文标签
        assertThat(vo.successType()).isEqualTo(GroupLinkImportSuccessType.ADOPTED.code());
        assertThat(vo.successTypeLabel()).isEqualTo("收编已有群");
        assertThat(vo.existingOrigin()).isEqualTo(GroupLinkOrigin.PULL_TASK.code());
        assertThat(vo.existingOriginLabel()).isEqualTo("拉群任务");
        assertThat(vo.failReason()).isNull();
        assertThat(vo.createdAt()).isEqualTo(EPOCH_2024_06_01_UTC);
    }

    @Test
    void toImportDetailVO_nullTime_returnsNullEpoch() {
        GroupLinkImportDetailVoRow row = new GroupLinkImportDetailVoRow();
        row.setLineNo(1);
        row.setResult(GroupLinkImportResult.SUCCESS.code());
        row.setCreatedAt(null);

        GroupLinkImportDetailVO vo = converter.toImportDetailVO(row);

        assertThat(vo.createdAt()).isNull();
    }

    @Test
    void toImportDetailVOList_convertsAll() {
        GroupLinkImportDetailVoRow row1 = new GroupLinkImportDetailVoRow();
        row1.setLineNo(1);
        row1.setResult(GroupLinkImportResult.SUCCESS.code());
        row1.setSuccessType(GroupLinkImportSuccessType.INSERTED.code());
        row1.setCreatedAt(EPOCH_2024_06_01_UTC);
        GroupLinkImportDetailVoRow row2 = new GroupLinkImportDetailVoRow();
        row2.setLineNo(2);
        row2.setResult(GroupLinkImportResult.FAILED.code());
        row2.setFailReason(GroupLinkImportFailReason.FORMAT_ERROR);
        row2.setCreatedAt(EPOCH_2024_06_01_UTC);

        List<GroupLinkImportDetailVO> vos = converter.toImportDetailVOList(List.of(row1, row2));

        assertThat(vos).hasSize(2);
        assertThat(vos.get(0).lineNo()).isEqualTo(1);
        assertThat(vos.get(0).resultLabel()).isEqualTo("成功");      // result=1
        assertThat(vos.get(0).successTypeLabel()).isEqualTo("新增");
        assertThat(vos.get(1).result()).isEqualTo(GroupLinkImportResult.FAILED.code());
        assertThat(vos.get(1).resultLabel()).isEqualTo("失败");  // result=2
        assertThat(vos.get(1).failReason()).isEqualTo("格式错误");
    }
}
