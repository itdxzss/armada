package com.armada.marketing.asset.controller;

import com.armada.marketing.asset.model.dto.ResourceAssetQuery;
import com.armada.marketing.asset.model.dto.ResourceAssetGroupDTO;
import com.armada.marketing.asset.model.dto.ResourceAssetMoveDTO;
import com.armada.marketing.asset.model.vo.ResourceAssetGroupVO;
import com.armada.marketing.asset.service.ResourceAssetGroupService;
import java.util.List;
import com.armada.marketing.asset.model.enums.ResourceAssetScope;
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

    /** 图片素材库业务服务。 */
    private final ResourceAssetService service;
    /** 素材分组业务服务。 */
    private final ResourceAssetGroupService groupService;

    /**
     * 创建素材接口控制器。
     *
     * @param groupService 分组业务服务
     * @param service 图片素材库业务服务
     */
    public ResourceAssetController(ResourceAssetService service, ResourceAssetGroupService groupService) {
        this.service = service;
        this.groupService = groupService;
    }

    /**
     * 分页查询当前租户素材，筛选和分页均由数据库执行。
     *
     * @param query 名称、标签、可选绑定条件和分页参数
     * @return 当前页素材元数据
     */
    @GetMapping
    @PreAuthorize("@resourceAssetAccess.allowed(#query.scope, 'view')")
    public ApiResponse<PageResult<ResourceAssetVO>> list(@ModelAttribute ResourceAssetQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * 查询当前租户活动素材使用中的标签候选。
     *
     * @return 按名称排序的标签候选
     */
    @GetMapping("/tags")
    @PreAuthorize("@resourceAssetAccess.allowed(#scope, 'view')")
    public ApiResponse<ResourceAssetTagsVO> tags(@RequestParam(defaultValue = "HYPERLINK") ResourceAssetScope scope) {
        return ApiResponse.ok(service.tags(scope));
    }

    /** @return 当前租户分组候选，权限与素材读取相同 */
    @GetMapping("/groups")
    @PreAuthorize("@resourceAssetAccess.allowed(#scope, 'view')")
    public ApiResponse<List<ResourceAssetGroupVO>> groups(@RequestParam(defaultValue = "HYPERLINK") ResourceAssetScope scope) {
        return ApiResponse.ok(groupService.list(scope));
    }

    /** @param request 分组名称 @return 新建的空分组 */
    @PostMapping("/groups")
    @PreAuthorize("@resourceAssetAccess.allowed(#scope, 'edit')")
    public ApiResponse<ResourceAssetGroupVO> createGroup(@RequestBody ResourceAssetGroupDTO request, @RequestParam(defaultValue = "HYPERLINK") ResourceAssetScope scope) {
        return ApiResponse.ok(groupService.create(request.groupName(), scope));
    }

    /** @param id 分组 ID @return 删除结果；图片移至未分组并保留所有引用 */
    @DeleteMapping("/groups/{id}")
    @PreAuthorize("@resourceAssetAccess.allowed(#scope, 'delete')")
    public ApiResponse<Void> deleteGroup(@PathVariable Long id, @RequestParam(defaultValue = "HYPERLINK") ResourceAssetScope scope) {
        groupService.delete(id, scope);
        return ApiResponse.ok();
    }

    /** @param request 批量素材与目标分组 @return 整批移组结果 */
    @PutMapping("/group")
    @PreAuthorize("@resourceAssetAccess.allowed(#scope, 'edit')")
    public ApiResponse<Void> moveGroup(@RequestBody ResourceAssetMoveDTO request, @RequestParam(defaultValue = "HYPERLINK") ResourceAssetScope scope) {
        groupService.move(request, scope);
        return ApiResponse.ok();
    }

    /**
     * 查询当前租户单个未删除素材详情。
     *
     * @param id 素材 ID
     * @return 素材详情
     */
    @GetMapping("/{id}")
    @PreAuthorize("@resourceAssetAccess.allowed(#scope, 'view')")
    public ApiResponse<ResourceAssetVO> detail(@PathVariable Long id, @RequestParam(defaultValue = "HYPERLINK") ResourceAssetScope scope) {
        return ApiResponse.ok(service.detail(id, scope));
    }

    /**
     * 校验并上传单张 JPEG/PNG，上传人取可信认证身份。
     *
     * @param file 待上传图片
     * @param tags 可选 JSON 字符串数组
     * @param groupId 上传分组，省略时未分组
     * @param principal 当前认证身份
     * @return 已创建素材详情
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@resourceAssetAccess.allowed(#scope, 'upload')")
    public ApiResponse<ResourceAssetVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String tags,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "HYPERLINK") ResourceAssetScope scope) {
        return ApiResponse.ok(service.upload(file, tags, principal.userId(), groupId, scope));
    }

    /**
     * 更新素材业务名称和标签。
     *
     * @param id 素材 ID
     * @param request 完整素材元数据
     * @return 更新后的素材详情
     */
    @PutMapping("/{id}")
    @PreAuthorize("@resourceAssetAccess.allowed(#scope, 'edit')")
    public ApiResponse<ResourceAssetVO> update(
            @PathVariable Long id,
            @RequestBody ResourceAssetUpdateDTO request,
            @RequestParam(defaultValue = "HYPERLINK") ResourceAssetScope scope) {
        return ApiResponse.ok(service.update(id, request, scope));
    }

    /**
     * 在无有效引用时软删除素材。
     *
     * @param id 素材 ID
     * @return data 固定为 null 的成功响应
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@resourceAssetAccess.allowed(#scope, 'delete')")
    public ApiResponse<Void> delete(@PathVariable Long id, @RequestParam(defaultValue = "HYPERLINK") ResourceAssetScope scope) {
        service.delete(id, scope);
        return ApiResponse.ok();
    }

    /**
     * 读取素材原始图片内容，响应禁止浏览器复用陈旧缓存。
     *
     * @param id 素材 ID
     * @return 图片字节响应
     */
    @GetMapping("/{id}/content")
    @PreAuthorize("@resourceAssetAccess.allowed(#scope, 'view')")
    public ResponseEntity<byte[]> content(@PathVariable Long id, @RequestParam(defaultValue = "HYPERLINK") ResourceAssetScope scope) {
        MarketingTemplateFileContent file = service.content(id, scope);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(file.content());
    }
}
