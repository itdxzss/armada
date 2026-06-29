package com.armada.group.mapper;

import com.armada.group.model.vo.GroupLinkHealthCheckCandidate;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.vo.GroupMemberLookupTarget;
import com.armada.group.model.vo.GroupLinkVoRow;
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
     * 插入新群链接(id/tenant_id 由库或拦截器注入,时间由调用方传入)。
     *
     * @param row 群链接实体
     * @return 影响行数
     */
    int insert(GroupLink row);

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

    /**
     * 群组列表分页总数(与 selectPageByLabel 共用 filter,口径一致)。
     *
     * @param query 查询参数(labelId 可为空;为空时查当前租户全量群组列表)
     * @return 活跃链接数
     */
    long countByLabel(GroupLinkQuery query);

    /**
     * 群组列表分页主查询,LEFT JOIN 预览、健康、来源批次与管理员聚合。
     *
     * @param query 查询参数(labelId 可为空;支持关键字、状态、来源文件、来源、关系态筛选)
     * @return 投影行列表
     */
    List<GroupLinkVoRow> selectPageByLabel(GroupLinkQuery query);

    /**
     * 按 ID 查询活跃群链接。
     *
     * @param id 群链接 ID
     * @return 活跃行;不存在或已软删时返回 null
     */
    GroupLink selectActiveById(@Param("id") Long id);

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
     * 查询群成员实时读取所需的群链接目标。
     *
     * @param id 群链接 ID
     * @return 活跃群链接及其已解析 groupJid;不存在时返回 null
     */
    GroupMemberLookupTarget selectMemberLookupTarget(@Param("id") Long id);

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
}
