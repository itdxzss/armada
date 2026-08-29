package com.armada.marketing.asset.service;

import com.armada.marketing.asset.model.dto.ResourceAssetQuery;
import com.armada.marketing.asset.model.dto.ResourceAssetUpdateDTO;
import com.armada.marketing.asset.model.vo.ResourceAssetTagsVO;
import com.armada.marketing.asset.model.vo.ResourceAssetVO;
import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.shared.response.PageResult;
import org.springframework.web.multipart.MultipartFile;

/** 当前租户共享图片素材库业务入口。 */
public interface ResourceAssetService {

    /**
     * 按名称、任意标签和可选绑定条件分页查询当前租户素材。
     *
     * @param query 素材筛选和分页参数
     * @return 当前页素材元数据、标签和引用统计
     */
    PageResult<ResourceAssetVO> list(ResourceAssetQuery query);

    /**
     * 查询当前租户单个未删除素材的完整管理元数据。
     *
     * @param id 素材 ID
     * @return 素材元数据、标签和引用统计
     */
    ResourceAssetVO detail(Long id);

    /**
     * 查询当前租户活动素材仍在使用的标签候选。
     *
     * @return 按标签名稳定排序的候选
     */
    ResourceAssetTagsVO tags();

    /**
     * 校验并上传单张 JPEG，同时在一个短事务内保存文件与公共标签。
     *
     * @param file 待上传图片
     * @param tagsJson 可选 JSON 字符串数组
     * @param createdBy 可信认证身份中的上传人用户 ID
     * @return 已创建素材详情
     */
    ResourceAssetVO upload(MultipartFile file, String tagsJson, long createdBy);

    /**
     * 更新当前租户素材名称与标签关系。
     *
     * @param id 素材 ID
     * @param request 完整名称与标签
     * @return 更新后的素材详情
     */
    ResourceAssetVO update(Long id, ResourceAssetUpdateDTO request);

    /**
     * 在无有效引用时软删除当前租户素材，并清理标签关系。
     *
     * @param id 素材 ID
     */
    void delete(Long id);

    /**
     * 读取当前租户素材的 MIME 与原始图片字节。
     *
     * @param id 素材 ID
     * @return 图片 MIME 与字节
     */
    MarketingTemplateFileContent content(Long id);
}
