package com.armada.group.converter;

import com.armada.group.model.vo.GroupLinkImportDetailVO;
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import com.armada.group.model.vo.GroupLinkLabelVO;
import com.armada.group.model.vo.GroupLinkLabelVoRow;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.model.vo.GroupLinkVoRow;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * group 域对象转换(MapStruct,编译期生成)。
 *
 * <p>时间字段统一为 Long epoch 毫秒,转换层直映。</p>
 */
@Mapper(componentModel = "spring")
public interface GroupConverter {

    /**
     * Mapper 投影行 → 出参 VO。
     *
     * @param row Mapper 查询投影
     * @return 前端出参
     */
    GroupLinkLabelVO toLabelVO(GroupLinkLabelVoRow row);

    /**
     * 批量转换。
     *
     * @param rows Mapper 查询投影列表
     * @return 前端出参列表
     */
    List<GroupLinkLabelVO> toLabelVOList(List<GroupLinkLabelVoRow> rows);

    /**
     * Mapper 投影行 → 群链接出参 VO。
     *
     * @param row Mapper 查询投影
     * @return 前端出参
     */
    GroupLinkVO toGroupLinkVO(GroupLinkVoRow row);

    /**
     * 批量转换群链接。
     *
     * @param rows Mapper 查询投影列表
     * @return 前端出参列表
     */
    List<GroupLinkVO> toGroupLinkVOList(List<GroupLinkVoRow> rows);

    /**
     * Mapper 投影行 → 导入明细出参 VO(
     * {@code resultLabel} 由 result 码经 {@link com.armada.group.model.GroupLinkImportResult} 算出中文标签)。
     *
     * @param row Mapper 查询投影
     * @return 前端出参
     */
    @Mapping(target = "resultLabel",
            expression = "java(com.armada.group.model.GroupLinkImportResult.fromCode(row.getResult()).label())")
    GroupLinkImportDetailVO toImportDetailVO(GroupLinkImportDetailVoRow row);

    /**
     * 批量转换导入明细。
     *
     * @param rows Mapper 查询投影列表
     * @return 前端出参列表
     */
    List<GroupLinkImportDetailVO> toImportDetailVOList(List<GroupLinkImportDetailVoRow> rows);

}
