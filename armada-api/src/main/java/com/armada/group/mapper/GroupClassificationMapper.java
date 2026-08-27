package com.armada.group.mapper;

import com.armada.group.model.vo.CanonicalGroupClassificationRow;
import com.armada.group.model.vo.CanonicalGroupClassificationWrite;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** canonical 群首次唯一分类数据访问。 */
@Mapper
public interface GroupClassificationMapper {

    /**
     * 确保账号控制事实对应的 canonical 群存在，但不据此定类。
     *
     * @param tenantId 当前租户 ID
     * @param groupJid 规范 WhatsApp 群 JID
     * @param now 当前时间(epoch 毫秒)
     * @return 新建行数；已存在时为零或驱动定义的 no-op 行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int ensureCanonicalGroup(
            @Param("tenantId") Long tenantId,
            @Param("groupJid") String groupJid,
            @Param("now") long now);

    /**
     * 按稳定 JID 顺序批量确保 canonical 群存在，避免大快照逐群写入。
     *
     * @param tenantId 当前租户 ID
     * @param groupJids 已排序的规范群 JID
     * @param now 当前时间(epoch 毫秒)
     * @return 数据库驱动报告的受影响行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int ensureCanonicalGroups(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids,
            @Param("now") long now);

    /**
     * 仅在 canonical 群尚未定类时原子写入首次分类及证据。
     *
     * <p>条件更新只允许 {@code UNCLASSIFIED} 行参与竞争，因此晚到旧事件、重放和相反候选
     * 均不能改写胜者。调用方必须先通过 {@link #ensureCanonicalGroup} 确保行存在。</p>
     *
     * @param tenantId 当前租户 ID
     * @param groupJid 规范 WhatsApp 群 JID
     * @param classificationCode 可靠候选分类码，只能为 1 或 2
     * @param sourceCode 首次分类证据来源码，只能为 1 或 2
     * @param classifiedAt 证据事实时间(epoch 毫秒)
     * @param updatedAt 本次写入时间(epoch 毫秒)
     * @return 一表示本次获胜，零表示已有胜者
     */
    @InterceptorIgnore(tenantLine = "true")
    int classifyFirst(
            @Param("tenantId") Long tenantId,
            @Param("groupJid") String groupJid,
            @Param("classificationCode") int classificationCode,
            @Param("sourceCode") int sourceCode,
            @Param("classifiedAt") long classifiedAt,
            @Param("updatedAt") long updatedAt);

    /**
     * 对已经由锁定 current read 确认为未分类的行批量写入首次分类。
     *
     * @param tenantId 当前租户 ID
     * @param writes 已按群 JID 排序的首次分类参数
     * @param updatedAt 本次写入时间(epoch 毫秒)
     * @return 实际获胜行数，应与 writes 数量一致
     */
    @InterceptorIgnore(tenantLine = "true")
    int classifyFirstBatch(
            @Param("tenantId") Long tenantId,
            @Param("writes") List<CanonicalGroupClassificationWrite> writes,
            @Param("updatedAt") long updatedAt);

    /**
     * 批量以 current read 读取当前租户 canonical 群的真实分类，供竞争输家收敛任务语义。
     * 锁定读不能退化为 REPEATABLE READ 的事务早期快照，否则输家可能看不到刚提交的胜者。
     *
     * @param tenantId 当前租户 ID
     * @param groupJids 规范群 JID，不能为空集合
     * @return 已存在群及其稳定分类码
     */
    @InterceptorIgnore(tenantLine = "true")
    List<CanonicalGroupClassificationRow> selectByGroupJids(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);
}
