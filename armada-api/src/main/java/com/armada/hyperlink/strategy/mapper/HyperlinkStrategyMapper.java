package com.armada.hyperlink.strategy.mapper;

import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyQuery;
import com.armada.hyperlink.strategy.model.entity.HyperlinkStrategy;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 超链策略数据访问；租户条件由 MyBatis-Plus 租户插件统一注入。 */
@Mapper
public interface HyperlinkStrategyMapper {

    /** 按页面筛选统计当前租户有效策略。 */
    long countPage(
            @Param("q") HyperlinkStrategyQuery query,
            @Param("taskType") Integer taskType);

    /** 按页面筛选分页查询当前租户有效策略。 */
    List<HyperlinkStrategy> selectPage(
            @Param("q") HyperlinkStrategyQuery query,
            @Param("taskType") Integer taskType);

    /** 按 ID 查询当前租户有效策略。 */
    HyperlinkStrategy selectById(@Param("id") Long id);

    /** 按所属任务读取当前租户任务快照。 */
    HyperlinkStrategy selectTaskSnapshotByOwner(@Param("taskId") long taskId);

    /** 查询启用策略候选，供任务创建或编辑时复制字段。 */
    List<HyperlinkStrategy> selectOptions(
            @Param("keyword") String keyword,
            @Param("limit") int limit);

    /** 查询有效同名策略是否存在，可排除正在编辑的策略。 */
    boolean existsByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    /** 新增策略并回填主键。 */
    int insert(HyperlinkStrategy entity);

    /** 按 ID 与期望版本完整更新策略并令版本加一。 */
    int updateByIdAndVersion(
            @Param("entity") HyperlinkStrategy entity,
            @Param("expectedVersion") int expectedVersion);

    /** 将新建任务 ID 绑定到预先插入的独占快照。 */
    int attachTaskOwner(@Param("id") long id, @Param("taskId") long taskId,
            @Param("updatedAt") long updatedAt);

    /** 更新任务独占快照；任务行乐观锁与外层事务负责并发仲裁。 */
    int updateTaskSnapshot(@Param("entity") HyperlinkStrategy entity,
            @Param("taskId") long taskId);

    /** 按 ID 软删除当前租户有效策略。 */
    int softDelete(@Param("id") Long id, @Param("deletedAt") long deletedAt);
}
