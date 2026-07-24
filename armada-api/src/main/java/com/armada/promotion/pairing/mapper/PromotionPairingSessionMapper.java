package com.armada.promotion.pairing.mapper;

import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 推广配对会话数据访问。公开查询和 Kafka 回调均显式关闭自动租户注入。 */
@Mapper
public interface PromotionPairingSessionMapper {
    /** 插入一条租户级配对会话，tenant_id 由租户拦截器注入。 */
    int insert(PromotionPairingSession row);

    /** 使用令牌摘要跨租户定位公开配对会话。 */
    @InterceptorIgnore(tenantLine = "true")
    PromotionPairingSession selectByTokenHash(@Param("tokenHash") String tokenHash);

    /** 使用一次性协议账号句柄定位活动会话。 */
    @InterceptorIgnore(tenantLine = "true")
    PromotionPairingSession selectActiveByProtocolAccountId(
            @Param("protocolAccountId") String protocolAccountId);

    /** 按会话和租户锁定一条记录，作为完成落库的并发边界。 */
    @InterceptorIgnore(tenantLine = "true")
    PromotionPairingSession selectByIdForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** 跨租户批量扫描已经到期但尚未结束的会话。 */
    @InterceptorIgnore(tenantLine = "true")
    List<PromotionPairingSession> selectExpiredActive(@Param("now") long now, @Param("limit") int limit);

    /** 记录本次配对临时占用的代理及协议侧 sticky 会话信息。 */
    @InterceptorIgnore(tenantLine = "true")
    int attachProxy(@Param("id") Long id, @Param("tenantId") Long tenantId,
                    @Param("proxyId") Long proxyId, @Param("proxySessionId") String proxySessionId,
                    @Param("proxyRegion") String proxyRegion, @Param("proxySource") String proxySource,
                    @Param("updatedAt") long updatedAt);

    /** 记录协议层受理结果及该次配对的最终到期时间。 */
    @InterceptorIgnore(tenantLine = "true")
    int markAccepted(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("pairingId") String pairingId, @Param("expiresAt") long expiresAt,
                     @Param("updatedAt") long updatedAt);

    /** 回填协议层随机生成的配对码并进入待手机确认状态。 */
    @InterceptorIgnore(tenantLine = "true")
    int markCodeGenerated(@Param("id") Long id,
                          @Param("tenantId") Long tenantId,
                          @Param("protocolAccountId") String protocolAccountId,
                          @Param("pairingCode") String pairingCode,
                          @Param("expiresAt") long expiresAt,
                          @Param("updatedAt") long updatedAt);

    /** 原子领取完成处理权，防止重复完成事件并发落库。 */
    @InterceptorIgnore(tenantLine = "true")
    int claimFinalizing(@Param("id") Long id, @Param("tenantId") Long tenantId,
                        @Param("updatedAt") long updatedAt);

    /** 将已经完成账号与代理落库的会话标记为成功。 */
    @InterceptorIgnore(tenantLine = "true")
    int markSucceeded(@Param("id") Long id, @Param("tenantId") Long tenantId,
                      @Param("accountId") Long accountId, @Param("completedAt") long completedAt);

    /** 将活动会话原子结束为失败或过期状态。 */
    @InterceptorIgnore(tenantLine = "true")
    int markTerminal(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("status") int status, @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage, @Param("completedAt") long completedAt);
}
