package com.armada.group.mapper;

import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.model.vo.GroupMemberQueryAccount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 账号当前在群关系数据访问。 */
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
     * @param accountId              账号 ID
     * @param baselineGroupJidsJson  去重后的群 JID JSON 数组
     * @param groupCount             JSON 数组长度
     * @param capturedAt             协议查询时间(epoch 毫秒)
     * @param now                    写库时间(epoch 毫秒)
     * @return 影响行数;账号已不是待拍状态时返回 0
     */
    int capturePendingAccountGroupBaseline(@Param("accountId") Long accountId,
                                           @Param("baselineGroupJidsJson") String baselineGroupJidsJson,
                                           @Param("groupCount") int groupCount,
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
     * 按群 JID 查租户内活跃 group_link。
     *
     * @param groupJid WhatsApp 群 JID
     * @return group_link.id;不存在时返回 null
     */
    Long selectActiveGroupLinkIdByGroupJid(@Param("groupJid") String groupJid);

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
    GroupMemberQueryAccount selectOnlineMemberQueryAccount(@Param("groupLinkId") Long groupLinkId,
                                                           @Param("onlineLoginState") int onlineLoginState);

    /**
     * 将本次回报中未出现的账号群关系标记为已不在群内。
     *
     * @param accountId 账号 ID
     * @param groupJids 本次回报且通过 baseline 差集后的群 JID
     * @param deletedAt 软删时间(epoch 毫秒)
     * @return 影响行数
     */
    int markMissingMembershipsDeleted(@Param("accountId") Long accountId,
                                      @Param("groupJids") List<String> groupJids,
                                      @Param("deletedAt") long deletedAt);
}
