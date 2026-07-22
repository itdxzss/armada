package com.armada.group.mapper;

import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.AccountGroupMembershipStatusRow;
import com.armada.group.model.vo.GroupExecutionAccount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 账号群关系当前状态数据访问。 */
@Mapper
public interface AccountGroupMembershipMapper {

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
     * 同步发现已有 group_link 时更新其展示名和关系态。
     *
     * @param groupLinkId 群入口 ID
     * @param groupName   协议返回群名,可空
     * @param updatedAt   更新时间(epoch 毫秒)
     * @return 影响行数
     */
    int touchGroupLinkFromAccountSync(@Param("groupLinkId") Long groupLinkId,
                                      @Param("groupName") String groupName,
                                      @Param("updatedAt") long updatedAt);

    /**
     * 账号群同步来源的群资料 upsert。
     *
     * @param groupLinkId  群入口 ID
     * @param groupJid     WhatsApp 群 JID
     * @param subject      群名称,可空
     * @param memberSize   群人数,可空
     * @param ownerPhone   群主号码,可空
     * @param announceOnly 是否仅管理员发言,可空
     * @param avatarUrl    群头像 URL,可空
     * @param syncAt       同步时间(epoch 毫秒)
     * @param now          写入时间(epoch 毫秒)
     * @return 影响行数
     */
    int upsertPreviewFromAccountSync(@Param("groupLinkId") Long groupLinkId,
                                     @Param("groupJid") String groupJid,
                                     @Param("subject") String subject,
                                     @Param("memberSize") Integer memberSize,
                                     @Param("ownerPhone") String ownerPhone,
                                     @Param("announceOnly") Boolean announceOnly,
                                     @Param("avatarUrl") String avatarUrl,
                                     @Param("syncAt") long syncAt,
                                     @Param("now") long now);

    /**
     * upsert 当前账号在群关系。
     *
     * @param row 关系行
     * @return 影响行数
     */
    int upsertMembership(AccountGroupMembership row);

    /**
     * 选择一个当前在线且在群内的账号,用于实时查询该群成员列表。
     *
     * @param groupLinkId      群链接 ID
     * @param onlineLoginState 在线登录态码
     * @return 查询账号;没有可用账号时返回 null
     */
    GroupExecutionAccount selectGroupExecutionAccount(@Param("groupLinkId") Long groupLinkId,
                                                       @Param("onlineLoginState") int onlineLoginState);

    /**
     * 将本次回报中未出现的账号群关系标记为已不在群内。
     *
     * @param accountId 账号 ID
     * @param groupJids 本次完整快照中的群 JID
     * @param status 缺失关系目标状态
     * @param preservedStatuses 不允许被完整快照降级的精确退出状态
     * @param source 状态来源
     * @param statusUpdatedAt 状态事实时间(epoch 毫秒)
     * @param updatedAt 更新时间(epoch 毫秒)
     * @return 影响行数
     */
    int markMissingMembershipsNotInGroup(
            @Param("accountId") Long accountId,
            @Param("groupJids") List<String> groupJids,
            @Param("status") int status,
            @Param("preservedStatuses") List<Integer> preservedStatuses,
            @Param("source") String source,
            @Param("statusUpdatedAt") long statusUpdatedAt,
            @Param("updatedAt") long updatedAt);
}
