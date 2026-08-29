package com.armada.account.mapper;

import com.armada.account.model.entity.AccountProfile;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 账号画像事实数据访问；所有写入显式校验租户与有效账号归属。 */
@Mapper
public interface AccountProfileMapper {

    /**
     * 按好友数独立同步水位幂等写入，旧事件和同水位冲突不覆盖。
     *
     * @param tenantId 当前租户 ID
     * @param accountId 账号 ID
     * @param friendCount 非负好友数
     * @param factAt 好友数事实时间
     * @param writtenAt 本次落库时间
     * @return 受影响行数；账号不属于当前租户或已删除时为 0
     */
    @InterceptorIgnore(tenantLine = "true")
    int upsertFriendCount(@Param("tenantId") long tenantId,
                          @Param("accountId") long accountId,
                          @Param("friendCount") int friendCount,
                          @Param("factAt") long factAt,
                          @Param("writtenAt") long writtenAt);

    /**
     * 按拉群隐私独立同步水位幂等写入，旧事件和同水位冲突不覆盖。
     *
     * @param tenantId 当前租户 ID
     * @param accountId 账号 ID
     * @param allowed 是否允许被拉群
     * @param factAt 隐私事实时间
     * @param writtenAt 本次落库时间
     * @return 受影响行数；账号不属于当前租户或已删除时为 0
     */
    @InterceptorIgnore(tenantLine = "true")
    int upsertGroupInviteAllowed(@Param("tenantId") long tenantId,
                                 @Param("accountId") long accountId,
                                 @Param("allowed") boolean allowed,
                                 @Param("factAt") long factAt,
                                 @Param("writtenAt") long writtenAt);

    /**
     * 按轮号独立更新时间水位幂等写入，旧事件和同水位冲突不覆盖。
     *
     * @param tenantId 当前租户 ID
     * @param accountId 账号 ID
     * @param status 轮号状态码
     * @param factAt 轮号事实时间
     * @param writtenAt 本次落库时间
     * @return 受影响行数；账号不属于当前租户或已删除时为 0
     */
    @InterceptorIgnore(tenantLine = "true")
    int upsertRotationStatus(@Param("tenantId") long tenantId,
                             @Param("accountId") long accountId,
                             @Param("status") int status,
                             @Param("factAt") long factAt,
                             @Param("writtenAt") long writtenAt);

    /**
     * 首次写入已知注册时间；冻结模型没有独立注册水位，后续事件不覆盖。
     *
     * @param tenantId 当前租户 ID
     * @param accountId 账号 ID
     * @param registeredAt 估算注册时间
     * @param source 注册时间来源码
     * @param writtenAt 本次落库时间
     * @return 受影响行数；账号不属于当前租户或已删除时为 0
     */
    @InterceptorIgnore(tenantLine = "true")
    int initializeRegistration(@Param("tenantId") long tenantId,
                               @Param("accountId") long accountId,
                               @Param("registeredAt") long registeredAt,
                               @Param("source") int source,
                               @Param("writtenAt") long writtenAt);

    /**
     * 按营销来源独立更新时间水位幂等写入，旧事件和同水位冲突不覆盖。
     *
     * @param tenantId 当前租户 ID
     * @param accountId 账号 ID
     * @param source 五类营销来源码
     * @param factAt 来源事实时间
     * @param writtenAt 本次落库时间
     * @return 受影响行数；账号不属于当前租户或已删除时为 0
     */
    @InterceptorIgnore(tenantLine = "true")
    int upsertMarketingSource(@Param("tenantId") long tenantId,
                              @Param("accountId") long accountId,
                              @Param("source") int source,
                              @Param("factAt") long factAt,
                              @Param("writtenAt") long writtenAt);

    /**
     * 按显式租户与账号 ID 读取画像；仅用于账号域和测试诊断。
     *
     * @param tenantId 当前租户 ID
     * @param accountId 账号 ID
     * @return 画像行；不存在时为 null
     */
    @InterceptorIgnore(tenantLine = "true")
    AccountProfile selectByTenantAndAccountId(@Param("tenantId") long tenantId,
                                               @Param("accountId") long accountId);
}
