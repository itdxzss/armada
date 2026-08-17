package com.armada.group.service;

import com.armada.group.model.dto.GroupMetadataPatch;

/**
 * 群资料字段级 patch 的领域写入口。
 *
 * <p>字段级 patch 与账号级完整快照最终写同一张 {@code wa_group_profile}，但决胜各按自己的
 * 版本进行：patch 按每字段版本，快照按整行水位。二者互不推进对方的水位，因此较新的精确事件
 * 不会被迟到的快照回滚，反之亦然（群变更事件直投影设计 §7.2）。</p>
 */
public interface GroupMetadataPatchService {

    /**
     * 按字段版本应用一次群资料 patch。
     *
     * <p>未进 fieldMask 的字段不覆盖数据库也不推进版本；进了 mask 但版本较旧的字段同样不写入，
     * 视为确认消费。群尚未建档时按 groupJid 创建最小群身份，不伪造邀请码或其他资料事实。</p>
     *
     * @param patch 字段级 patch
     * @return 是否有字段实际写入（false 表示全部被 mask 过滤或版本判旧）
     */
    boolean applyPatch(GroupMetadataPatch patch);
}
