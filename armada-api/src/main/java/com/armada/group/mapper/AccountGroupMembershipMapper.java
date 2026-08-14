package com.armada.group.mapper;

import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMembershipStatusRow;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.model.vo.HistoricalGroupAccountPhoneRow;
import com.armada.group.model.vo.HistoricalGroupPageRow;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 账号群关系当前状态数据访问。 */
@Mapper
public interface AccountGroupMembershipMapper {

    /**
     * 统计当前租户账号组历史群并集。
     *
     * @param accountGroupId 账号组 ID
     * @return 可展示的去重历史群数量
     */
    default long countHistoricalGroupsByAccountGroup(Long accountGroupId) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return countHistoricalGroupsByTenantAndAccountGroup(tenantId, accountGroupId);
    }

    /** 显式租户版历史群统计,用于绕过 JSON_TABLE 的租户 SQL 自动改写。 */
    @InterceptorIgnore(tenantLine = "true")
    long countHistoricalGroupsByTenantAndAccountGroup(
            @Param("tenantId") Long tenantId,
            @Param("accountGroupId") Long accountGroupId);

    /**
     * 分页读取当前租户账号组历史群并集。
     *
     * @param accountGroupId 账号组 ID
     * @param offset SQL 偏移量
     * @param pageSize 每页数量
     * @return 群级聚合行
     */
    default List<HistoricalGroupPageRow> selectHistoricalGroupPageByAccountGroup(
            Long accountGroupId,
            int offset,
            int pageSize) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return selectHistoricalGroupPageByTenantAndAccountGroup(
                tenantId,
                accountGroupId,
                offset,
                pageSize);
    }

    /** 显式租户版历史群分页,用于绕过 JSON_TABLE 的租户 SQL 自动改写。 */
    @InterceptorIgnore(tenantLine = "true")
    List<HistoricalGroupPageRow> selectHistoricalGroupPageByTenantAndAccountGroup(
            @Param("tenantId") Long tenantId,
            @Param("accountGroupId") Long accountGroupId,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    /**
     * 批量读取当前页历史群的关联账号号码。
     *
     * <p>当前真实在群账号优先；尚无当前在群事实时，调用方使用 baseline 账号作为离线展示兜底。</p>
     *
     * @param accountGroupId 账号组 ID
     * @param groupJids 当前页群 JID
     * @return 群与账号号码关系
     */
    default List<HistoricalGroupAccountPhoneRow> selectHistoricalGroupAccountPhonesByAccountGroup(
            Long accountGroupId,
            List<String> groupJids) {
        if (groupJids == null || groupJids.isEmpty()) {
            return List.of();
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return selectHistoricalGroupAccountPhonesByTenantAndAccountGroup(
                tenantId, accountGroupId, groupJids);
    }

    /** 显式租户版历史群关联账号查询,用于绕过 JSON_TABLE 的租户 SQL 自动改写。 */
    @InterceptorIgnore(tenantLine = "true")
    List<HistoricalGroupAccountPhoneRow> selectHistoricalGroupAccountPhonesByTenantAndAccountGroup(
            @Param("tenantId") Long tenantId,
            @Param("accountGroupId") Long accountGroupId,
            @Param("groupJids") List<String> groupJids);

    /** 判断群 JID 是否属于账号组内任一账号的历史 baseline。 */
    default boolean existsHistoricalGroupByAccountGroup(
            Long accountGroupId,
            String groupJid) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return existsHistoricalGroupByTenantAndAccountGroup(
                tenantId, accountGroupId, groupJid);
    }

    /** 显式租户版历史群范围判断。 */
    @InterceptorIgnore(tenantLine = "true")
    boolean existsHistoricalGroupByTenantAndAccountGroup(
            @Param("tenantId") Long tenantId,
            @Param("accountGroupId") Long accountGroupId,
            @Param("groupJid") String groupJid);

    /** 从账号组内选择在线、正常且仍在群内的管理员账号。 */
    default GroupExecutionAccount selectHistoricalGroupExecutionAccount(
            Long accountGroupId,
            String groupJid) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return selectHistoricalGroupExecutionAccountByTenant(
                tenantId, accountGroupId, groupJid);
    }

    /** 显式租户版历史群管理员选择。 */
    @InterceptorIgnore(tenantLine = "true")
    GroupExecutionAccount selectHistoricalGroupExecutionAccountByTenant(
            @Param("tenantId") Long tenantId,
            @Param("accountGroupId") Long accountGroupId,
            @Param("groupJid") String groupJid);

    /**
     * 按租户和群 JID 返回可为任务管理员提权的我方在线管理员候选。
     *
     * <p>本查询不受账号分组约束；候选仅来自当前租户可控账号，并按群主、最近在群时间、
     * 账号 ID 稳定排序。调用方提交写操作前仍需实时确认权限。</p>
     *
     * @param tenantId 当前租户 ID
     * @param groupJid 目标 WhatsApp 群 JID
     * @param managerAccountId 待提权任务管理员账号 ID
     * @return 按优先级排序的候选账号
     */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupExecutionAccount> selectPullTaskAdminPromoterCandidatesByTenant(
            @Param("tenantId") Long tenantId,
            @Param("groupJid") String groupJid,
            @Param("managerAccountId") Long managerAccountId);

    /**
     * 返回管理员事实待确认的在线在群受控账号，供一次定点成员查询使用。
     *
     * @param tenantId 当前租户 ID
     * @param groupJid 目标 WhatsApp 群 JID
     * @param managerAccountId 待提权任务管理员账号 ID
     * @param limit 最大候选数量
     * @return 按账号 ID 稳定排序的候选
     */
    @InterceptorIgnore(tenantLine = "true")
    List<GroupExecutionAccount> selectPullTaskAdminDiscoveryCandidatesByTenant(
            @Param("tenantId") Long tenantId,
            @Param("groupJid") String groupJid,
            @Param("managerAccountId") Long managerAccountId,
            @Param("limit") int limit);

    /**
     * 查询账号群 baseline 状态。
     *
     * @param accountId 账号 ID
     * @return 活跃账号的 baseline 行;账号不存在或已删除时返回 null
     */
    AccountGroupBaselineRow selectAccountBaselineRow(@Param("accountId") Long accountId);

    /**
     * 捕获待拍账号的当前全部群作为 baseline JSON。
     *
     * <p>JID 数组和静态群名映射在同一条 INSERT 中首次写入;唯一键冲突时不覆盖历史快照。
     * 租户 ID 只允许由租户拦截器从 {@code TenantContext} 注入,避免调用方跨租户覆盖。</p>
     *
     * @param baseline   首次快照行,包含账号 ID、JID JSON、群名 JSON 与群数量
     * @param capturedAt 协议查询时间(epoch 毫秒)
     * @param now        写库时间(epoch 毫秒)
     * @return 影响行数;账号已不是待拍状态时返回 0
     */
    int capturePendingAccountGroupBaseline(@Param("baseline") AccountGroupBaselineRow baseline,
                                           @Param("capturedAt") long capturedAt,
                                           @Param("now") long now);

    /**
     * 将待拍账号标记为已拍 baseline。
     *
     * @param accountId 账号 ID
     * @param now       更新时间(epoch 毫秒)
     * @return 影响行数
     */
    int markAccountBaselineCaptured(@Param("accountId") Long accountId,
                                    @Param("now") long now);

    /**
     * 查询账号刷新前仍可发送的群 JID。
     *
     * @param accountId 账号 ID
     * @param sendableStatuses 可发送关系状态码
     * @return 当前可发送群 JID，按关系创建顺序排列
     */
    List<String> selectSendableGroupJids(@Param("accountId") Long accountId,
                                         @Param("sendableStatuses") List<Integer> sendableStatuses);

    /**
     * 查询快照刷新前已经由快照或其它稳定来源确认的可发送群。
     *
     * <p>精确 WGP2 add 暂不算快照已建立关系，使随后首次完整快照仍能触发现有新增群即时营销。</p>
     */
    List<String> selectSnapshotEstablishedGroupJids(
            @Param("accountId") Long accountId,
            @Param("sendableStatuses") List<Integer> sendableStatuses);

    /**
     * 批量查询当前租户内账号群关系状态。
     *
     * @param lookups 账号 ID 与群 JID 复合键
     * @return 当前状态行
     */
    List<AccountGroupMembershipStatusRow> selectCurrentStatuses(
            @Param("lookups") List<AccountGroupMembershipLookup> lookups);

    /**
     * 按群 JID 查租户内 group_link，优先返回活跃行，必要时允许复用软删除行。
     *
     * @param groupJid WhatsApp 群 JID
     * @return group_link.id;不存在时返回 null
     */
    Long selectGroupLinkIdByGroupJidIncludingDeleted(@Param("groupJid") String groupJid);

    /**
     * 批量查询已存在的群资料主键。
     *
     * <p>使用普通一致性读区分存量行与新增行，避免在 RR 下先对不存在的唯一键执行 UPDATE
     * 并持有 gap/supremum 锁。</p>
     *
     * @param groupLinkIds 群入口 ID
     * @return 已存在的群入口 ID
     */
    List<Long> selectExistingPreviewGroupLinkIds(@Param("groupLinkIds") List<Long> groupLinkIds);

    /**
     * 同步发现已有 group_link 时更新其展示名和关系态。
     *
     * @param groupLinkId 群入口 ID
     * @param groupName   协议返回群名,可空
     * @param syncProtocolMask 本次观察协议位
     * @param updatedAt   更新时间(epoch 毫秒)
     * @return 影响行数
     */
    int touchGroupLinkFromAccountSync(@Param("groupLinkId") Long groupLinkId,
                                      @Param("groupName") String groupName,
                                      @Param("syncProtocolMask") int syncProtocolMask,
                                      @Param("updatedAt") long updatedAt);

    /**
     * 账号群同步来源的群资料 upsert。
     *
     * @param row 群资料行，包含本次群主身份是否已观察的非持久化标记
     * @return 影响行数
     */
    int upsertPreviewFromAccountSync(GroupLinkPreview row);

    /**
     * 更新账号群同步已存在的群资料，避免存量行进入自增 INSERT 候选锁路径。
     *
     * @param row 群资料行
     * @return 影响行数；不存在时返回 0，由调用方执行原子 upsert 兜底
     */
    int updatePreviewFromAccountSync(GroupLinkPreview row);

    /**
     * upsert 当前账号在群关系。
     *
     * @param row 关系行
     * @return 影响行数
     */
    int upsertMembership(AccountGroupMembership row);

    /**
     * 批量查询账号当前未软删除的群关系。
     *
     * <p>该查询为普通一致性读，不获取不存在键的 gap 锁。状态不是筛选条件，确保退出态等存量行
     * 仍通过与通用 upsert 等价的更新规则合并。</p>
     *
     * @param accountId 账号 ID
     * @param groupJids 群 JID
     * @return 已存在的群 JID
     */
    List<String> selectExistingActiveGroupJids(@Param("accountId") Long accountId,
                                                @Param("groupJids") List<String> groupJids);

    /**
     * 按与 {@link #upsertMembership(AccountGroupMembership)} 相同的状态优先级更新当前关系。
     *
     * @param row 关系行
     * @return 影响行数；当前活跃关系不存在时返回 0，由调用方执行原子 upsert 兜底
     */
    int updateActiveMembership(AccountGroupMembership row);

    /**
     * 选择一个当前在线且在群内的账号,用于实时查询该群成员列表。
     *
     * @param groupLinkId      群链接 ID
     * @param onlineLoginState 在线登录态码
     * @return 有界候选账号列表
     */
    List<GroupExecutionAccount> selectGroupExecutionAccounts(
            @Param("groupLinkId") Long groupLinkId,
            @Param("onlineLoginState") int onlineLoginState,
            @Param("normalAccountState") int normalAccountState,
            @Param("limit") int limit);

    /**
     * 选择一个当前在线、在群且群角色为管理员的账号,用于刷新群邀请链接等必须管理员权限的操作。
     *
     * <p>与 {@link #selectGroupExecutionAccounts} 的差别是 is_admin 参与过滤而非仅参与排序,
     * 口径与群组列表"可用管理员"列一致,避免列表显示可用却选出普通成员执行。</p>
     *
     * @param groupLinkId 群链接 ID
     * @param onlineLoginState 在线登录态码
     * @param normalAccountState 正常账号态码
     * @param limit 最大候选数
     * @return 按最近在群时间稳定排序的管理员候选;无管理员时为空列表
     */
    List<GroupExecutionAccount> selectGroupAdminExecutionAccounts(
            @Param("groupLinkId") Long groupLinkId,
            @Param("onlineLoginState") int onlineLoginState,
            @Param("normalAccountState") int normalAccountState,
            @Param("limit") int limit);

    /**
     * 从新鲜 metadata 已确认的管理员手机号中选择当前群租户的在线正常账号。
     *
     * <p>完整 metadata 是本次实时群成员事实；首次同步时账号群关系可能尚未写入，
     * 因此本查询不能把旧关系行作为准入条件。</p>
     *
     * @param groupLinkId 群入口 ID
     * @param phones 新鲜 metadata 确认的管理员手机号
     * @param onlineLoginState 在线登录态码
     * @param normalAccountState 正常账号态码
     * @param limit 最大候选数
     * @return 按最近在群时间稳定排序的候选
     */
    List<GroupExecutionAccount> selectGroupExecutionAccountsByPhones(
            @Param("groupLinkId") Long groupLinkId,
            @Param("phones") List<String> phones,
            @Param("onlineLoginState") int onlineLoginState,
            @Param("normalAccountState") int normalAccountState,
            @Param("limit") int limit);

    /**
     * 从最新完整成员快照解析当前租户已上控成员及其群角色。
     *
     * <p>仅按已确认手机号连接未软删账号；外部成员、号码未知成员和其他租户账号不会返回。
     * 调用方使用现有时序保护 upsert，将完整 metadata 事实合并进账号群关系。</p>
     *
     * @param groupLinkId 群入口 ID
     * @return 按账号 ID 稳定排序的上控成员关系事实
     */
    List<AccountGroupMembership> selectControlledMembershipsByGroupLinkId(
            @Param("groupLinkId") Long groupLinkId);

    /**
     * 普通一致性读选出本次完整快照中缺失的账号群关系 ID。
     *
     * @param accountId 账号 ID
     * @param groupJids 本次完整快照中的群 JID
     * @param preservedStatuses 不允许被完整快照降级的精确退出状态
     * @param statusUpdatedAt 状态事实时间(epoch 毫秒)
     * @return 缺失关系 ID，按主键升序
     */
    List<Long> selectMissingMembershipIds(
            @Param("accountId") Long accountId,
            @Param("groupJids") List<String> groupJids,
            @Param("preservedStatuses") List<Integer> preservedStatuses,
            @Param("statusUpdatedAt") long statusUpdatedAt);

    /**
     * 按主键定点更新完整快照中缺失的账号群关系，并再次校验状态时序。
     *
     * @param ids 缺失关系 ID，调用方按主键升序传入
     * @param row 缺失关系目标状态与时间
     * @param preservedStatuses 不允许被完整快照降级的精确退出状态
     * @return 影响行数
     */
    int markMembershipsNotInGroupByIds(
            @Param("ids") List<Long> ids,
            @Param("row") AccountGroupMembership row,
            @Param("preservedStatuses") List<Integer> preservedStatuses);
}
