package com.armada.group.mapper;

import com.armada.group.model.dto.GroupMetadataPatchRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 群资料字段级 patch 的写入边界。
 *
 * <p>与 {@link AccountGroupCurrentSnapshotMapper} 的整行快照写入分开：字段级 patch 的决胜按
 * 每字段独立版本进行，不能共用整行水位。两者写同一张 {@code wa_group_profile}，靠列级 CASE
 * 表达式各管自己的字段与版本列，互不覆盖。</p>
 */
@Mapper
public interface GroupMetadataPatchMapper {

    /**
     * 按 groupJid 查询群主键。
     *
     * @param groupJid WhatsApp 群 JID
     * @return 群主键，不存在时返回 null
     */
    Long selectGroupIdByJid(@Param("groupJid") String groupJid);

    /**
     * 为尚未建档的群创建最小群身份，仅落 groupJid 与时间。
     *
     * <p>幂等：并发下重复插入由唯一键收敛为更新时间，不产生第二行。</p>
     *
     * @param groupJid WhatsApp 群 JID
     * @param now      当前时间(epoch 毫秒)
     * @return 影响行数
     */
    int insertMinimalGroup(@Param("groupJid") String groupJid, @Param("now") long now);

    /**
     * 按字段版本写入群资料 patch。
     *
     * <p>只有 {@code *ObservedAt} 非空的字段参与写入，且必须在版本决胜中胜出；未观察或已过时的
     * 字段保持库中原值与原版本。整行水位 {@code metadata_observed_at} 不被本语句推进。</p>
     *
     * @param row 已归约 mask 的 patch 行
     * @return 影响行数
     */
    int upsertFieldPatch(@Param("row") GroupMetadataPatchRow row);
}
