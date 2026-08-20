package com.armada.group.mapper;

import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMessageSendPermissionRow;
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
     * 批量查询当前租户内账号群关系状态。
     *
     * @param lookups 账号 ID 与群 JID 复合键
     * @return 当前状态行
     */
    List<AccountGroupMembershipStatusRow> selectCurrentStatuses(
            @Param("lookups") List<AccountGroupMembershipLookup> lookups);

    /**
     * 批量查询当前租户内账号群发言权限。
     *
     * @param lookups 账号 ID 与群 JID 复合键
     * @return 当前发言权限行；权限或账号角色事实不足时允许值为空
     */
    List<AccountGroupMessageSendPermissionRow> selectCurrentMessageSendPermissions(
            @Param("lookups") List<AccountGroupMembershipLookup> lookups);

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
            @Param("executableAccountStates") List<Integer> executableAccountStates,
            @Param("limit") int limit);

    /**
     * 按群 metadata 已确认的群主号码选择当前在线、正常且仍在群内的执行账号。
     *
     * <p>群权限写操作必须由群主账号执行，不允许回退到其它管理员或普通成员。
     * 本查询不依赖可能滞后的 {@code account_group_membership.is_admin} 标记。</p>
     *
     * @param groupLinkId 群链接 ID
     * @param onlineLoginState 在线登录态码
     * @param executableAccountStates 可执行账号生命周期状态集合
     * @return 群主账号；群主身份未知、离线或不再在群时返回 null
     */
    GroupExecutionAccount selectGroupOwnerExecutionAccount(
            @Param("groupLinkId") Long groupLinkId,
            @Param("onlineLoginState") int onlineLoginState,
            @Param("executableAccountStates") List<Integer> executableAccountStates);

    /**
     * 选择一个当前在线、在群且群角色为管理员的账号,用于刷新群邀请链接等必须管理员权限的操作。
     *
     * <p>与 {@link #selectGroupExecutionAccounts} 的差别是 is_admin 参与过滤而非仅参与排序,
     * 口径与群组列表"可用管理员"列一致,避免列表显示可用却选出普通成员执行。</p>
     *
     * @param groupLinkId 群链接 ID
     * @param onlineLoginState 在线登录态码
     * @param executableAccountStates 可执行账号生命周期状态集合
     * @param limit 最大候选数
     * @return 按最近在群时间稳定排序的管理员候选;无管理员时为空列表
     */
    List<GroupExecutionAccount> selectGroupAdminExecutionAccounts(
            @Param("groupLinkId") Long groupLinkId,
            @Param("onlineLoginState") int onlineLoginState,
            @Param("executableAccountStates") List<Integer> executableAccountStates,
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
     * @param executableAccountStates 可执行账号生命周期状态集合
     * @param limit 最大候选数
     * @return 按最近在群时间稳定排序的候选
     */
    List<GroupExecutionAccount> selectGroupExecutionAccountsByPhones(
            @Param("groupLinkId") Long groupLinkId,
            @Param("phones") List<String> phones,
            @Param("onlineLoginState") int onlineLoginState,
            @Param("executableAccountStates") List<Integer> executableAccountStates,
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

}
