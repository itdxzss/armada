package com.armada.resource.controller;

import com.armada.resource.model.dto.GroupDataPackageCreateDTO;
import com.armada.resource.model.dto.GroupDataPackageUpdateDTO;
import com.armada.resource.model.dto.GroupDataPackageExportDTO;
import com.armada.resource.model.dto.GroupDataPackageQuery;
import com.armada.resource.model.dto.GroupDataPackagePhoneQuery;
import com.armada.resource.model.vo.GroupDataPackageVO;
import com.armada.resource.model.vo.GroupDataPackagePhoneVO;
import com.armada.resource.model.vo.GroupDataPackageImportVO;
import com.armada.resource.model.vo.GroupDataPackageImportResultVO;
import com.armada.resource.model.vo.GroupDataPackageCountryVO;
import com.armada.resource.model.vo.GroupDataPackageExportVO;
import com.armada.resource.service.GroupDataPackageService;
import com.armada.resource.service.GroupDataPackageImportService;
import com.armada.resource.service.GroupDataPackageExportService;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

/** 拉群数据包动态菜单接口；操作权限与资源归属在服务端校验。 */
@RestController
@RequestMapping("/api/group-data-packages")
@PreAuthorize("hasAuthority('tenant:group_data_package:view')")
public class GroupDataPackageController {
    private final GroupDataPackageService service;
    private final GroupDataPackageImportService imports;
    private final GroupDataPackageExportService exports;
    private final CountryService countries;
    /** 注入资源业务服务。 */
    public GroupDataPackageController(GroupDataPackageService service, GroupDataPackageImportService imports,
            GroupDataPackageExportService exports, CountryService countries) {
        this.service = service; this.imports = imports; this.exports = exports; this.countries = countries;
    }
    /** 按服务器筛选分页。 */
    @GetMapping
    public ApiResponse<PageResult<GroupDataPackageVO>> list(@ModelAttribute GroupDataPackageQuery query) {
        return ApiResponse.ok(service.list(query));
    }
    /** 国家与大洲候选使用现有国家主数据。 */
    @GetMapping("/countries")
    public ApiResponse<List<GroupDataPackageCountryVO>> countries() {
        return ApiResponse.ok(countries.options("marketing-export").rows().stream()
                .filter(row -> row.iso2() != null)
                .map(row -> new GroupDataPackageCountryVO(row.iso2(), row.nameZh(), row.continentCode())).toList());
    }
    /** 数据包详情和当前指标。 */
    @GetMapping("/{id}")
    public ApiResponse<GroupDataPackageVO> detail(@PathVariable long id) { return ApiResponse.ok(service.detail(id)); }
    /** 创建独立空包。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:group_data_package:create')")
    public ApiResponse<GroupDataPackageVO> create(@RequestBody GroupDataPackageCreateDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) { return ApiResponse.ok(service.create(request, principal.userId())); }
    /** 带版本更新名称和备注。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:group_data_package:edit')")
    public ApiResponse<GroupDataPackageVO> update(@PathVariable long id, @RequestBody GroupDataPackageUpdateDTO request) {
        return ApiResponse.ok(service.update(id, request));
    }
    /** TXT追加或覆盖导入，默认过滤最近60天可靠隐私拒绝。 */
    @PostMapping(value="/{id}/import", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('tenant:group_data_package:import')")
    public ApiResponse<GroupDataPackageImportResultVO> importPhones(@PathVariable long id,
            @ModelAttribute ImportForm form, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(imports.importPhones(new GroupDataPackageImportService.ImportRequest(
                id, form.getMode(), form.getFile(), form.getPrivacyFilterDays(), principal.userId())));
    }
    /** 查询导入审计，不输出原始文件内容。 */
    @GetMapping("/{id}/imports")
    public ApiResponse<PageResult<GroupDataPackageImportVO>> imports(@PathVariable long id,
            @ModelAttribute GroupDataPackageQuery query) { return ApiResponse.ok(imports.imports(id, query)); }
    /** 查看可定位到原料行号的号码状态。 */
    @GetMapping("/{id}/phones")
    public ApiResponse<PageResult<GroupDataPackagePhoneVO>> phones(@PathVariable long id,
            @ModelAttribute GroupDataPackagePhoneQuery query) { return ApiResponse.ok(service.phones(id, query)); }
    /** 只回收可安全重试的明确失败。 */
    @PostMapping("/{id}/reset-failed")
    @PreAuthorize("hasAuthority('tenant:group_data_package:edit')")
    public ApiResponse<Integer> resetFailed(@PathVariable long id) { return ApiResponse.ok(service.resetFailed(id)); }
    /** 软删除资源入口，保留执行证据。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:group_data_package:delete')")
    public ApiResponse<Void> delete(@PathVariable long id) { service.delete(id); return ApiResponse.ok(); }
    /** 单包状态导出。 */
    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('tenant:group_data_package:export')")
    public ResponseEntity<byte[]> export(@PathVariable long id, @RequestParam(defaultValue="all") String usageStatus,
            @RequestParam(defaultValue="txt") String format) { return response(exports.export(List.of(id), usageStatus, format)); }
    /** 多包同状态导出，总量有限制。 */
    @PostMapping("/export")
    @PreAuthorize("hasAuthority('tenant:group_data_package:export')")
    public ResponseEntity<byte[]> export(@RequestBody GroupDataPackageExportDTO request) {
        return response(exports.export(request.ids(), request.usageStatus() == null ? "all" : request.usageStatus(),
                request.format() == null ? "txt" : request.format()));
    }
    private static ResponseEntity<byte[]> response(GroupDataPackageExportVO file) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build());
        headers.setContentLength(file.bytes().length); headers.set("X-Export-Count", String.valueOf(file.exportedCount()));
        headers.setAccessControlExposeHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION, "X-Export-Count"));
        return ResponseEntity.ok().headers(headers).body(file.bytes());
    }
    /** multipart表单，不接收客户端租户或用户身份。 */
    public static class ImportForm {
        /** 上传文件。 */
        private MultipartFile file;
        /** 默认追加，不隐式覆盖。 */
        private String mode = "append";
        /** 近期可靠隐私拒绝历史筛选窗口。 */
        private int privacyFilterDays = 60;
        /** 上传文件。 */ public MultipartFile getFile() { return file; }
        /** 上传文件。 */ public void setFile(MultipartFile value) { file = value; }
        /** 追加或覆盖。 */ public String getMode() { return mode; }
        /** 追加或覆盖。 */ public void setMode(String value) { mode = value; }
        /** 0不筛或1至365天。 */ public int getPrivacyFilterDays() { return privacyFilterDays; }
        /** 0不筛或1至365天。 */ public void setPrivacyFilterDays(int value) { privacyFilterDays = value; }
    }
}
