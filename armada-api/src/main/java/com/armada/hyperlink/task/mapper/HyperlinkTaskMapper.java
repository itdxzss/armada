package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskDetailRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskListQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 超链任务配置 Mapper。 */
@Mapper
public interface HyperlinkTaskMapper {
    int insert(HyperlinkTask entity);
    HyperlinkTask selectById(@Param("id") long id);
    /** 按显式租户一次读取任务、内容和运行态详情。 */
    @InterceptorIgnore(tenantLine = "true")
    HyperlinkTaskDetailRow selectDetailById(@Param("tenantId") long tenantId,
            @Param("id") long id);
    int updateConfig(@Param("entity") HyperlinkTask entity, @Param("expectedVersion") int expectedVersion);
    int incrementVersion(@Param("id") long id, @Param("expectedVersion") int expectedVersion,
            @Param("updatedAt") long updatedAt);
    int updatePackageSnapshot(@Param("id") long id, @Param("generation") int generation,
            @Param("packageName") String packageName, @Param("countryIso2s") String countryIso2s,
            @Param("updatedAt") long updatedAt);
    long countList(@Param("q") HyperlinkTaskListQuery query);
    List<HyperlinkTaskListRow> selectList(@Param("q") HyperlinkTaskListQuery query);
    List<HyperlinkTaskListRow> selectExport(@Param("q") HyperlinkTaskListQuery query);
}
