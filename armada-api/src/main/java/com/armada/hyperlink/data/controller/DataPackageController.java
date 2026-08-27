package com.armada.hyperlink.data.controller;

import com.armada.hyperlink.data.model.dto.DataPackageCreateDTO;
import com.armada.hyperlink.data.model.dto.DataPackagePhoneQuery;
import com.armada.hyperlink.data.model.dto.DataPackageQuery;
import com.armada.hyperlink.data.model.dto.DataPackageUpdateDTO;
import com.armada.hyperlink.data.model.enums.DataPackageImportMode;
import com.armada.hyperlink.data.model.vo.DataPackageCountryOptionVO;
import com.armada.hyperlink.data.model.vo.DataPackageDetailVO;
import com.armada.hyperlink.data.model.vo.DataPackageImportResultVO;
import com.armada.hyperlink.data.model.vo.DataPackageListItemVO;
import com.armada.hyperlink.data.model.vo.DataPackagePhoneItemVO;
import com.armada.hyperlink.data.service.DataPackageImportService;
import com.armada.hyperlink.data.service.DataPackageService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
import org.springframework.http.MediaType;
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

/** 超链数据包一期 HTTP 接口；仅接收参数、权限上下文并组装统一响应信封。 */
@RestController
@RequestMapping("/api/data-packages")
@PreAuthorize("hasAuthority('tenant:hyperlink_data:view')")
public class DataPackageController {

    private final DataPackageService service;
    private final DataPackageImportService importService;

    public DataPackageController(
            DataPackageService service,
            DataPackageImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    /** 查询数据包分页；forTask=true 时只返回仍有未使用号码的有效包。 */
    @GetMapping
    public ApiResponse<PageResult<DataPackageListItemVO>> list(
            @ModelAttribute DataPackageQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /** 读取启用国家候选，并固定在末尾追加 UNKNOWN。 */
    @GetMapping("/countries")
    public ApiResponse<List<DataPackageCountryOptionVO>> countries() {
        return ApiResponse.ok(service.countries());
    }

    /** 查询当前租户下未删除的数据包详情。 */
    @GetMapping("/{id}")
    public ApiResponse<DataPackageDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /** 创建空数据包。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:hyperlink_data:create')")
    public ApiResponse<DataPackageDetailVO> create(
            @RequestBody DataPackageCreateDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(request, userId(principal)));
    }

    /** 按完整名称、备注和乐观锁版本编辑数据包。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:hyperlink_data:edit')")
    public ApiResponse<DataPackageDetailVO> update(
            @PathVariable Long id,
            @RequestBody DataPackageUpdateDTO request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /** TXT 追加或 generation 覆盖导入。 */
    @PostMapping(value = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('tenant:hyperlink_data:import')")
    public ApiResponse<DataPackageImportResultVO> importPhones(
            @PathVariable Long id,
            @RequestParam("mode") String mode,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(importService.importPhones(
                id, DataPackageImportMode.fromApi(mode), file, userId(principal)));
    }

    /** 分页查询父包当前 generation 的号码明细。 */
    @GetMapping("/{id}/phones")
    public ApiResponse<PageResult<DataPackagePhoneItemVO>> phones(
            @PathVariable Long id,
            @ModelAttribute DataPackagePhoneQuery query) {
        return ApiResponse.ok(service.phones(id, query));
    }

    /** 软删除数据包；号码保留 30 天后由后台分批清理。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:hyperlink_data:delete')")
    public ApiResponse<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        service.delete(id, userId(principal));
        return ApiResponse.ok();
    }

    private static Long userId(AuthPrincipal principal) {
        return principal == null ? null : principal.userId();
    }
}
