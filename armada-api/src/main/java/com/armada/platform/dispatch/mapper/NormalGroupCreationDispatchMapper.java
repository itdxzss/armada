package com.armada.platform.dispatch.mapper;

import com.armada.platform.dispatch.model.NormalGroupCreationDispatchCandidate;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 新建普群调度扫描基础设施。
 *
 * <p>只有该 Mapper 允许跨租户读取待调度的小页；业务更新仍在恢复租户上下文后完成。</p>
 */
@Mapper
public interface NormalGroupCreationDispatchMapper {

    /** 读取索引命中的待发布小页。 */
    @InterceptorIgnore(tenantLine = "true")
    List<NormalGroupCreationDispatchCandidate> selectPendingDispatches(
            @Param("now") long now, @Param("limit") int limit);

    /** 读取索引命中的过期执行租约小页。 */
    @InterceptorIgnore(tenantLine = "true")
    List<NormalGroupCreationDispatchCandidate> selectExpiredProcessing(
            @Param("processingBefore") long processingBefore,
            @Param("limit") int limit);
}
