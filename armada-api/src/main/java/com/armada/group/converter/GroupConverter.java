package com.armada.group.converter;

import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
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
    default GroupLinkVO toGroupLinkVO(GroupLinkVoRow row) {
        if (row == null) {
            return null;
        }
        GroupStatus status = resolveStatus(row);
        GroupLinkOrigin origin = GroupLinkOrigin.fromCode(row.getOrigin());
        GroupMembershipState membershipState = GroupMembershipState.fromCode(row.getMembershipState());
        return new GroupLinkVO(
                row.getId(),
                row.getUrl(),
                displayGroupName(row),
                row.getWaSubject(),
                row.getGroupJid(),
                row.getSourceFileName(),
                status.code(),
                status.label(),
                row.getHealthStatus(),
                row.getBanned(),
                row.getCurrentCount() == null ? row.getMemberSize() : row.getCurrentCount(),
                row.getAdmin(),
                row.getOrigin(),
                origin == null ? null : origin.label(),
                row.getMembershipState(),
                membershipState == null ? null : membershipState.label(),
                row.getRemark(),
                row.getAvatarUrl(),
                row.getOwnerPhone(),
                row.getLastPreviewAt(),
                row.getLastCheckAt(),
                row.getLastHealthError(),
                row.getCreatedAt());
    }

    /**
     * 批量转换群链接。
     *
     * @param rows Mapper 查询投影列表
     * @return 前端出参列表
     */
    default List<GroupLinkVO> toGroupLinkVOList(List<GroupLinkVoRow> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(this::toGroupLinkVO).toList();
    }

    /**
     * Mapper 投影行 → 导入明细出参 VO(
     * {@code resultLabel} 由 result 码经 {@link com.armada.group.model.GroupLinkImportResult} 算出中文标签)。
     *
     * @param row Mapper 查询投影
     * @return 前端出参
     */
    @Mapping(target = "resultLabel",
            expression = "java(com.armada.group.model.GroupLinkImportResult.fromCode(row.getResult()).label())")
    @Mapping(target = "successTypeLabel",
            expression = "java(row.getSuccessType() == null ? null : com.armada.group.model.enums.GroupLinkImportSuccessType.fromCode(row.getSuccessType()).label())")
    @Mapping(target = "existingOriginLabel",
            expression = "java(row.getExistingOrigin() == null ? null : com.armada.group.model.enums.GroupLinkOrigin.fromCode(row.getExistingOrigin()).label())")
    GroupLinkImportDetailVO toImportDetailVO(GroupLinkImportDetailVoRow row);

    /**
     * 批量转换导入明细。
     *
     * @param rows Mapper 查询投影列表
     * @return 前端出参列表
     */
    List<GroupLinkImportDetailVO> toImportDetailVOList(List<GroupLinkImportDetailVoRow> rows);

    private static String displayGroupName(GroupLinkVoRow row) {
        if (hasText(row.getGroupName())) {
            return row.getGroupName();
        }
        return row.getWaSubject();
    }

    /**
     * 计算群组列表展示状态。封禁优先于健康状态;无健康记录或 health_status 为空时视为未检测。
     */
    private static GroupStatus resolveStatus(GroupLinkVoRow row) {
        if (Boolean.TRUE.equals(row.getBanned())) {
            return new GroupStatus("BANNED", "封禁");
        }
        GroupLinkHealthStatus healthStatus = GroupLinkHealthStatus.fromCode(row.getHealthStatus());
        if (healthStatus == null) {
            return new GroupStatus("UNCHECKED", "未检测");
        }
        return switch (healthStatus) {
            case AVAILABLE -> new GroupStatus("AVAILABLE", healthStatus.label());
            case LINK_INVALID -> new GroupStatus("LINK_INVALID", healthStatus.label());
            case UNAVAILABLE -> new GroupStatus("UNAVAILABLE", healthStatus.label());
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 群组列表对前端暴露的状态码与中文文案。 */
    record GroupStatus(String code, String label) {
    }

}
