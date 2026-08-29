package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 超链任务内容 Mapper。 */
@Mapper
public interface HyperlinkTaskContentMapper {
    int insert(HyperlinkTaskContent entity);
    HyperlinkTaskContent selectByTaskId(@Param("taskId") long taskId);
    int update(HyperlinkTaskContent entity);
}
