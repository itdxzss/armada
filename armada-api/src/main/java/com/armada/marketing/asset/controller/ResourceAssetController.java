package com.armada.marketing.asset.controller;

import com.armada.marketing.asset.model.dto.ResourceAssetQuery;
import com.armada.marketing.asset.model.dto.ResourceAssetUpdateDTO;
import com.armada.marketing.asset.model.vo.ResourceAssetTagsVO;
import com.armada.marketing.asset.model.vo.ResourceAssetVO;
import com.armada.marketing.asset.service.ResourceAssetService;
import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 超链模板、任务和独立管理页共用的图片素材 API。 */
@RestController
@RequestMapping("/api/resource-assets")
public class ResourceAssetController {

    /** 查看素材、模板或任务时允许读取素材元数据和图片内容。 */
    private static final String READ_AUTHORITIES = "hasAnyAuthority("
            + "'tenant:resource_asset:view', "
            + "'tenant:hyperlink_template:view', 'tenant:hyperlink_template:create', "
            + "'tenant:hyperlink_template:edit', 'tenant:hyperlink_task:view', "
            + "'tenant:hyperlink_task:create', 'tenant:hyperlink_task:edit')";

    /** 独立素材上传权限或模板、任务编辑权限均可进入共享上传入口。 */
    private static final String UPLOAD_AUTHORITIES = "hasAnyAuthority("
            + "'tenant:resource_asset:upload', "
            + "'tenant:hyperlink_template:create', 'tenant:hyperlink_template:edit', "
            + "'tenant:hyperlink_task:create', 'tenant:hyperlink_task:edit')";

    /** 图片素材库业务服务。 */
    private final ResourceAssetService service;

    /**
     * 创建素材接口控制器。
     *
     * @param service 图片素材库业务服务
     */
    public ResourceAssetController(ResourceAssetService service) {
        this.service = service;
    }

    /**
     * 分页查询当前租户素材，筛选和分页均由数据库执行。
     *
     * @param query 名称、标签、可选绑定条件和分页参数
     * @return 当前页素材元数据
     */
    @GetMapping
    @PreAuthorize(READ_AUTHORITIES)
    public ApiResponse<PageResult<ResourceAssetVO>> list(@ModelAttribute ResourceAssetQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * 查询当前租户活动素材使用中的标签候选。
     *
     * @return 按名称排序的标签候选
     */
    @GetMapping("/tags")
    @PreAuthorize(READ_AUTHORITIES)
    public ApiResponse<ResourceAssetTagsVO> tags() {
        return ApiResponse.ok(service.tags());
    }

    /**
     * 查询当前租户单个未删除素材详情。
     *
     * @param id 素材 ID
     * @return 素材详情
     */
    @GetMapping("/{id}")
    @PreAuthorize(READ_AUTHORITIES)
    public ApiResponse<ResourceAssetVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /**
     * 校验并上传单张 JPEG，上传人取可信认证身份。
     *
     * @param file 待上传图片
     * @param tags 可选 JSON 字符串数组
     * @param principal 当前认证身份
     * @return 已创建素材详情
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(UPLOAD_AUTHORITIES)
    public ApiResponse<ResourceAssetVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String tags,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.upload(file, tags, principal.userId()));
    }

    /**
     * 更新素材业务名称和标签。
     *
     * @param id 素材 ID
     * @param request 完整素材元数据
     * @return 更新后的素材详情
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:resource_asset:edit')")
    public ApiResponse<ResourceAssetVO> update(
            @PathVariable Long id,
            @RequestBody ResourceAssetUpdateDTO request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /**
     * 在无有效引用时软删除素材。
     *
     * @param id 素材 ID
     * @return data 固定为 null 的成功响应
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:resource_asset:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    /**
     * 读取素材原始图片内容，响应禁止浏览器复用陈旧缓存。
     *
     * @param id 素材 ID
     * @return 图片字节响应
     */
    @GetMapping("/{id}/content")
    @PreAuthorize(READ_AUTHORITIES)
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        MarketingTemplateFileContent file = service.content(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(file.content());
    }
}
