package com.armada.group.mapper;

import com.armada.group.model.entity.GroupLinkPreview;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群链接协议预览元数据访问。 */
@Mapper
public interface GroupLinkPreviewMapper {

    /**
     * 批量保留旧列表仍使用的创建者手机号、国家和洲口径。
     *
     * <p>旧预览表退役期间只允许这三个兼容字段继续写入，其它群资料以新模型为准。</p>
     *
     * @param rows 创建者兼容字段及观察时间
     * @return 影响行数
     */
    int upsertCreatorCompatibility(@Param("rows") List<GroupLinkPreview> rows);

}
