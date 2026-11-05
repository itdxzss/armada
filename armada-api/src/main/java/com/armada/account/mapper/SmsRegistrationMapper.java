package com.armada.account.mapper;

import com.armada.account.model.entity.SmsRegistration;
import com.armada.account.model.enums.SmsRegistrationState;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 接码订单 Go 注册办理的数据访问；仅后台扫描跨租户，其余读写均由租户插件约束。 */
@Mapper
public interface SmsRegistrationMapper {
    /** 保存办理记录并回填主键；订单已有记录时由唯一键拒绝。 */
    int insert(SmsRegistration row);
    /** 当前租户内某订单的办理记录，没有则返回 null（MyBatis 单行查询）。 */
    SmsRegistration findByActivation(@Param("activationId") Long activationId);
    /** 当前租户内某订单处于指定状态的记录数。 */
    int countByActivationAndStates(@Param("activationId") Long activationId, @Param("states") List<Integer> states);
    /** @return 当前租户内某订单处于“后台推进中”（申请中/等码/验证中）的记录数，大于 0 表示订单被占用 */
    default int countActiveByActivation(Long activationId) {
        return countByActivationAndStates(activationId, SmsRegistrationState.activeCodes());
    }
    /** 当前租户内一批订单的办理记录，供订单列表附带办理状态。 */
    List<SmsRegistration> listByActivationIds(@Param("activationIds") List<Long> activationIds);
    /**
     * 跨租户取出处于指定状态的记录，调用方必须按每条记录的租户恢复上下文后再做任何读写。
     * @param limit 单轮最多取出条数，防止积压时一轮过长
     */
    @InterceptorIgnore(tenantLine = "true")
    List<SmsRegistration> listByStates(@Param("states") List<Integer> states, @Param("limit") int limit);
    /** @return 跨租户的“后台推进中”记录，用法同 {@link #listByStates} */
    default List<SmsRegistration> listActive(int limit) {
        return listByStates(SmsRegistrationState.activeCodes(), limit);
    }
    /** 跨租户扫描已结束但代理尚未释放的记录；调用方必须按记录恢复租户上下文。 */
    @InterceptorIgnore(tenantLine = "true")
    List<SmsRegistration> listPendingProxyRelease(@Param("states") List<Integer> states, @Param("limit") int limit);
    /** 代理释放成功后清空占用标记，仅允许清理当前租户、指定代理的终态记录。 */
    int clearReleasedProxy(@Param("id") Long id, @Param("proxyId") Long proxyId, @Param("states") List<Integer> states);
    /**
     * 仅当仍处于原状态时迁移，防止并发把终态改回；事务 ID、version 用 COALESCE 保证只写一次。
     * @param row 携带 id、新状态、事务 ID、version、失败原因与变更时间
     * @param from 期望的原状态值
     * @return 更新行数
     */
    int transition(@Param("row") SmsRegistration row, @Param("from") int from);
}
