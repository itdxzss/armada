package com.armada.group.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 旧群当前事实向新模型分批回填的数据访问；当前只实现 wa_group 阶段。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface GroupModelBackfillMapper {

    /**
     * 统计 JID 非法或找不到同租户旧群入口的预览来源。
     *
     * @return 非法来源行数
     */
    int countInvalidGroupSources();

    /**
     * 统计租户内映射到同一规范化群 JID 的多条旧入口。
     *
     * @return 冲突的租户群数量
     */
    int countDuplicateGroupJids();

    /**
     * 按租户和群 JID 顺序回填一批群身份及本地字段。
     *
     * @param limit 单批最大群数
     * @return 实际插入或更新行数
     */
    int backfillGroups(@Param("limit") int limit);
}
