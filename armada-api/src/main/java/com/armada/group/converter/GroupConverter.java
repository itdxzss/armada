package com.armada.group.converter;

import com.armada.group.model.vo.GroupLinkImportDetailVO;
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import com.armada.group.model.vo.GroupLinkLabelVO;
import com.armada.group.model.vo.GroupLinkLabelVoRow;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.model.vo.GroupLinkVoRow;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * group 域对象转换(MapStruct,编译期生成)。
 *
 * <p>时间统一转 epoch 毫秒(按 UTC 解释 LocalDateTime,对标 MarketingTemplateConverter)。</p>
 */
@Mapper(componentModel = "spring")
public interface GroupConverter {

    /**
     * Mapper 投影行 → 出参 VO(时间字段由 {@link #toEpochMilli} 自动转换)。
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
     * Mapper 投影行 → 群链接出参 VO(时间字段由 {@link #toEpochMilli} 自动转换)。
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
     * Mapper 投影行 → 导入明细出参 VO(时间字段由 {@link #toEpochMilli} 自动转换)。
     *
     * @param row Mapper 查询投影
     * @return 前端出参
     */
    GroupLinkImportDetailVO toImportDetailVO(GroupLinkImportDetailVoRow row);

    /**
     * 批量转换导入明细。
     *
     * @param rows Mapper 查询投影列表
     * @return 前端出参列表
     */
    List<GroupLinkImportDetailVO> toImportDetailVOList(List<GroupLinkImportDetailVoRow> rows);

    /**
     * {@code LocalDateTime} → epoch 毫秒(UTC 解释);供 MapStruct 按类型自动选用。
     *
     * <p>库表 {@code DATETIME} 配 {@code serverTimezone=UTC} 存的是 UTC 墙钟,
     * MyBatis 读成不带时区的 {@code LocalDateTime},必须按 {@code ZoneOffset.UTC} 解释。</p>
     *
     * @param t 时间(可为 null)
     * @return epoch 毫秒,或 null
     */
    default Long toEpochMilli(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
