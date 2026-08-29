package com.armada.marketing.asset.mapper;

import com.armada.marketing.asset.model.entity.ResourceAssetTag;
import com.armada.marketing.asset.model.vo.ResourceAssetTagRelationVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 图片素材标签字典与关系数据访问。 */
@Mapper
public interface ResourceAssetTagMapper {

    /**
     * 幂等插入当前租户标签字典；唯一键冲突时保持原行。
     *
     * @param tag 标签名称与创建时间
     * @return 实际插入行数
     */
    int insertIgnore(ResourceAssetTag tag);

    /**
     * 按精确、大小写敏感名称查询当前租户标签。
     *
     * @param names 已归一化标签名
     * @return 与输入名称匹配的标签实体
     */
    List<ResourceAssetTag> selectByNames(@Param("names") List<String> names);

    /**
     * 删除当前租户指定素材的全部标签关系。
     *
     * @param fileId 素材文件 ID
     * @return 删除行数
     */
    int deleteRefsByFileId(@Param("fileId") Long fileId);

    /**
     * 幂等插入当前租户单条素材标签关系。
     *
     * @param fileId 素材文件 ID
     * @param tagId 标签 ID
     * @param createdAt 关系创建时间，epoch 毫秒
     * @return 实际插入行数
     */
    int insertRefIgnore(
            @Param("fileId") Long fileId,
            @Param("tagId") Long tagId,
            @Param("createdAt") long createdAt);

    /**
     * 批量查询当前租户一页素材的标签关系。
     *
     * @param fileIds 当前页素材 ID
     * @return 按素材和关系创建顺序排序的标签关系
     */
    List<ResourceAssetTagRelationVO> selectRelationsByFileIds(@Param("fileIds") List<Long> fileIds);

    /**
     * 查询当前租户仍被活动素材使用的标签候选。
     *
     * @return 按标签名稳定排序的候选
     */
    List<String> selectActiveTagNames();
}
