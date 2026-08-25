package com.armada.group.mapper;

import com.armada.group.model.vo.AccountObservedGroupHandle;
import com.armada.group.model.vo.AccountObservedGroupWrite;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.vo.GroupLinkHealthCheckCandidate;
import com.armada.group.model.vo.GroupCurrentIdentity;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 群链接数据访问。tenant_id 由租户行隔离拦截器自动注入,SQL 不手写 tenant_id 过滤。
 */
@Mapper
public interface GroupLinkMapper {

    /**
     * 按 URL 查群链接(含软删记录),供 upsert 时判断是否已存在(需复活则 adoptToLabel)。
     *
     * @param url 归一化链接
     * @return 找到则返回实体(含 deletedAt),否则 null
     */
    GroupLink selectAnyByUrl(@Param("url") String url);

    /**
     * 按 URL 当前读群入口（含软删记录）。
     *
     * <p>用于唯一键 upsert 后解析最终 ID；RR 快照读可能看不到等待期间由其它事务提交的行，
     * 当前读可读取最新已提交版本。调用方必须处于写事务内。</p>
     *
     * @param url 归一化链接
     * @return 找到则返回并锁定实体，否则 null
     */
    GroupLink selectAnyByUrlForUpdate(@Param("url") String url);

    /**
     * 插入新群链接(id/tenant_id 由库或拦截器注入,时间由调用方传入)。
     *
     * @param row 群链接实体
     * @return 影响行数
     */
    int insert(GroupLink row);

    /**
     * 原子登记账号同步观察到的内部群入口。
     *
     * <p>租户内 URL 唯一键承担并发互斥；命中既有行时保留首次来源、导入归属和自建群关系态，
     * 只执行账号同步原有的复活、群名和已入群状态更新。</p>
     *
     * @param row               新建分支的群入口字段；调用方写后按 URL 查询最终行 ID
     * @param observedGroupName 协议观察到的群名；空值不覆盖既有群名
     * @return MySQL 影响行数
     */
    int upsertAccountObservedGroup(@Param("row") GroupLink row,
                                   @Param("observedGroupName") String observedGroupName);

    /** 按群 JID 解析仍保留的数字句柄，必要时允许复用软删除行。 */
    Long selectIdByGroupJidIncludingDeleted(@Param("groupJid") String groupJid);

    /** 按请求群 JID 批量解析应复用的稳定兼容句柄。 */
    @InterceptorIgnore(tenantLine = "true")
    List<AccountObservedGroupHandle> selectAccountObservedHandles(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);

    /** 批量登记前按主键升序锁定已解析句柄，包含待复活软删行。 */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupLink> selectAccountObservedByIdsForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("ids") List<Long> ids);

    /** 按内部 URL 唯一键批量登记仍未解析到句柄的账号观察群。 */
    @InterceptorIgnore(tenantLine = "true")
    int upsertAccountObservedGroups(
            @Param("tenantId") Long tenantId,
            @Param("rows") List<AccountObservedGroupWrite> rows);

    /** 同步发现已有句柄时只更新句柄自身的名称、关系态和协议来源。 */
    int touchAccountObservedGroup(@Param("groupLinkId") Long groupLinkId,
                                  @Param("groupName") String groupName,
                                  @Param("syncProtocolMask") int syncProtocolMask,
                                  @Param("updatedAt") long updatedAt);

    /** 按新模型当前邀请码解析仍活跃的数字句柄。 */
    Long selectActiveIdByInviteCode(@Param("inviteCode") String inviteCode);

    /**
     * 复活软删链接并归到目标分组:复活(deleted_at=NULL) + 改归属分组 + 更新来源批次 + COALESCE 群名(空不覆盖)。
     *
     * @param id        群链接 ID
     * @param labelId   目标分组 ID
     * @param batchId   来源批次 ID
     * @param groupName 新群名(null 时保留原值)
     * @return 影响行数
     */
    int adoptToLabel(@Param("id") Long id, @Param("labelId") Long labelId,
                     @Param("batchId") Long batchId, @Param("groupName") String groupName,
                     @Param("updatedAt") long updatedAt);

    /**
     * 将活跃但尚未归入导入分组的群入口收编到导入链接分组,不改变首次来源。
     *
     * @param id        群链接 ID
     * @param labelId   目标导入分组 ID
     * @param batchId   当前导入批次 ID
     * @param updatedAt 更新时间(epoch毫秒)
     * @return 影响行数
     */
    int adoptActiveIntoImport(@Param("id") Long id, @Param("labelId") Long labelId,
                              @Param("batchId") Long batchId, @Param("updatedAt") long updatedAt);

    /**
     * 复活软删群入口为独立群组池目标,不归入导入链接分组。
     *
     * @param id        群链接 ID
     * @param updatedAt 更新时间(epoch毫秒)
     * @return 影响行数
     */
    int reviveAsStandaloneTarget(@Param("id") Long id, @Param("updatedAt") long updatedAt);

    int markSelfBuiltGroup(@Param("id") Long id,
                           @Param("groupName") String groupName,
                           @Param("updatedAt") long updatedAt);

    /** 把活动群入口的历史群事实提升为真；永不清除。 */
    int markHistorical(@Param("groupLinkId") Long groupLinkId,
                       @Param("updatedAt") long updatedAt);

    /** 把活动群入口的上控后群事实提升为真；永不清除。 */
    int markPostControl(@Param("groupLinkId") Long groupLinkId,
                        @Param("updatedAt") long updatedAt);

    /**
     * 按主键升序一次提升完整快照中的历史群和上控后群分类。
     *
     * @param historicalIds 本轮需要提升为历史群的句柄 ID
     * @param postControlIds 本轮需要提升为上控后群的句柄 ID
     * @param updatedAt 分类事实更新时间(epoch毫秒)
     * @return 实际提升的句柄行数
     */
    int markClassifications(
            @Param("historicalIds") List<Long> historicalIds,
            @Param("postControlIds") List<Long> postControlIds,
            @Param("updatedAt") long updatedAt);

    /**
     * 按 ID 查询活跃群链接。
     *
     * @param id 群链接 ID
     * @return 活跃行;不存在或已软删时返回 null
     */
    GroupLink selectActiveById(@Param("id") Long id);

    /** 按稳定群入口 ID 读取新模型当前群 JID 和邀请码。 */
    GroupCurrentIdentity selectCurrentIdentity(@Param("id") Long id);

    /** 按群 JID 查询当前租户活动群入口 ID。 */
    Long selectActiveIdByGroupJid(@Param("groupJid") String groupJid);

    /**
     * 更新群组列表本地资料字段。
     *
     * @param id        群链接 ID
     * @param groupName 运营侧自定义群名称;可为 null
     * @param remark    运营备注;可为 null
     * @param updatedAt 更新时间(epoch毫秒)
     * @return 影响行数
     */
    int updateProfile(@Param("id") Long id,
                      @Param("groupName") String groupName,
                      @Param("remark") String remark,
                      @Param("updatedAt") long updatedAt);

    /**
     * 仅更新运营侧自定义群名称,不触碰备注。
     *
     * @param id        群链接 ID
     * @param groupName 新群名;可为 null
     * @param updatedAt 更新时间(epoch毫秒)
     * @return 影响行数
     */
    int updateGroupName(@Param("id") Long id,
                        @Param("groupName") String groupName,
                        @Param("updatedAt") long updatedAt);

    /**
     * 群链接健康检查候选:跨租户返回已解析 group_jid 且能找到在线在群账号的活动链接。
     *
     * <p>后台调度线程没有租户上下文,因此关闭租户拦截器,SQL 内显式按 tenant_id 连接各表。
     * 每个群链接只返回一个操作账号:管理员优先,再按 join_task_result.id 兜底。</p>
     *
     * @param limit            本轮最大候选数
     * @param onlineLoginState 在线登录态码
     * @return 可发起协议层 metadata 检测的候选
     */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupLinkHealthCheckCandidate> selectHealthCheckCandidates(
            @Param("limit") int limit,
            @Param("onlineLoginState") int onlineLoginState);

    /**
     * 按 ID 批量查活跃群链接。
     *
     * @param ids 群链接 ID 列表
     * @return 活跃群链接列表;不存在或已软删记录不会返回
     */
    List<GroupLink> selectActiveByIds(@Param("ids") List<Long> ids);

    /**
     * 批量迁移到目标分组(改 label_id)。
     *
     * @param ids     群链接 ID 列表
     * @param labelId 目标分组 ID
     * @return 影响行数
     */
    int migrateToLabel(@Param("ids") List<Long> ids, @Param("labelId") Long labelId,
                       @Param("updatedAt") long updatedAt);

    /**
     * 按 ID 批量软删除群链接。
     *
     * @param ids 群链接 ID 列表
     * @return 影响行数
     */
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);

    /**
     * 按所属分组 ID 批量软删除群链接(分组被删时级联调用)。
     *
     * @param labelIds 分组 ID 列表
     * @return 更新行数
     */
    int softDeleteByLabelIds(@Param("ids") List<Long> labelIds, @Param("deletedAt") long deletedAt);

    /**
     * 计算 ID 列表中活跃链接数(迁移/删除存在性校验)。
     *
     * @param ids 群链接 ID 列表
     * @return 活跃链接数
     */
    int countActiveByIds(@Param("ids") List<Long> ids);

    /** 统计指定运营分组下的活跃群组数。 */
    int countActiveByFolderIds(@Param("folderIds") List<Long> folderIds);

    /** 删除运营分组前将关联活跃群组移入未分组。 */
    int clearFolderByFolderIds(@Param("folderIds") List<Long> folderIds,
                               @Param("updatedAt") long updatedAt);

    /**
     * 按 ID 升序锁定当前租户的活跃群组。
     *
     * @param ids 已去重并排序的群组 ID
     * @return 当前租户内存在的活跃群组
     */
    default List<GroupLink> selectActiveByIdsForUpdate(List<Long> ids) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return selectByTenantAndIdsForUpdate(tenantId, ids);
    }

    /** 使用显式租户执行锁行查询，避免租户插件改写 FOR UPDATE 尾句。 */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupLink> selectByTenantAndIdsForUpdate(@Param("tenantId") Long tenantId,
                                                   @Param("ids") List<Long> ids);

    /** 批量设置或清空群组的运营分组。 */
    int assignFolder(@Param("ids") List<Long> ids,
                     @Param("folderId") Long folderId,
                     @Param("updatedAt") long updatedAt);
}
