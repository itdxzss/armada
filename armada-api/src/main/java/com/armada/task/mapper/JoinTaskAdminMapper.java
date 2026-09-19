package com.armada.task.mapper;

import com.armada.task.model.dto.JoinTaskDispatchCandidate;
import com.armada.task.model.entity.JoinTaskResult;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 进群后管理员阶段的持久化与租约；进群结果始终保留。 */
@Mapper
public interface JoinTaskAdminMapper {
    /** 跨租户只扫描 ID，处理时必须恢复租户上下文。 */
    @InterceptorIgnore(tenantLine = "true")
    List<JoinTaskDispatchCandidate> scan(@Param("now") long now, @Param("limit") int limit);

    /** 锁定当前租户的明细，不跨事务持锁等待网络。 */
    JoinTaskResult lock(@Param("id") Long id);

    /** 读取发命令前的明细，供 Outbox 补全器校验。 */
    JoinTaskResult find(@Param("id") Long id);

    /** 只更新管理员阶段，调用方须已持明细行锁。 */
    int update(JoinTaskResult row);
}
