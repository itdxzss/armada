package com.armada.account.controller;

import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.dto.AccountImportDetailQuery;
import com.armada.account.model.dto.AccountImportForm;
import com.armada.account.model.dto.AccountImportQuery;
import com.armada.account.model.vo.AccountImportBatchListVO;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.account.model.vo.AccountImportDetailVO;
import com.armada.account.service.AccountImportService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 账号导入端点(C1 上传导入、C2 批次列表、C3 明细列表、C4 明细导出)。
 *
 * <p>Controller 只做参数接收与响应组装,业务规则全部在 Service。</p>
 */
@RestController
@RequestMapping("/api/account-imports")
public class AccountImportController {

    private final AccountImportService service;

    public AccountImportController(AccountImportService service) {
        this.service = service;
    }

    /**
     * C1 上传导入账号(multipart/form-data)。
     *
     * <p>导入元信息封装为 {@link AccountImportForm}({@code @ModelAttribute} 绑定);上传文件单独以
     * {@code @RequestParam} 接收。表单 {@code text} 与 {@code file} 二选一,均空时由 Service 抛
     * BusinessException。</p>
     *
     * @param form 导入元信息表单(分组/格式/机型/类型/IP/备注/文本)
     * @param file 上传文件(可选;与 form.text 二选一)
     * @return 导入批次 VO(含计数统计及批次 ID)
     */
    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    public ApiResponse<AccountImportBatchVO> importAccounts(
            @ModelAttribute AccountImportForm form,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        byte[] fileBytes;
        try {
            fileBytes = (file != null && !file.isEmpty()) ? file.getBytes() : null;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "文件读取失败");
        }

        String sourceFileName = (file != null && !file.isEmpty()) ? file.getOriginalFilename() : null;
        AccountImportDTO meta = new AccountImportDTO(
                form.getAccountGroupId(), form.getImportFormat(), form.getDeviceOs(), form.getAccountType(),
                form.getIpRegion(), form.getRemark(), sourceFileName);
        return ApiResponse.ok(service.importAccounts(meta, fileBytes, form.getText()));
    }

    /**
     * C2 导入批次分页列表。
     *
     * @param query 查询条件(分页 + 可选筛选字段)
     * @return 分页批次列表
     */
    @GetMapping
    public ApiResponse<PageResult<AccountImportBatchListVO>> listBatches(
            @ModelAttribute AccountImportQuery query) {
        return ApiResponse.ok(service.listBatches(query));
    }

    /**
     * C3 指定批次的导入明细分页列表。
     *
     * @param batchId 批次 ID
     * @param query   查询条件(filter 可选,默认 all)
     * @return 分页明细列表
     */
    @GetMapping("/{batchId}/details")
    public ApiResponse<PageResult<AccountImportDetailVO>> listDetails(
            @PathVariable Long batchId,
            @ModelAttribute AccountImportDetailQuery query) {
        query.setBatchId(batchId);
        return ApiResponse.ok(service.listDetails(query));
    }

    /**
     * C4 导出指定批次明细为 CSV 文件。
     *
     * <p>响应:UTF-8 BOM + 表头 + 数据行,Content-Disposition=attachment。
     * 导出不走 ApiResponse 信封,直接返回 {@code ResponseEntity<byte[]>} 文件流。</p>
     *
     * @param batchId 批次 ID
     * @param scope   结果范围:all(默认)/success/fail
     * @return CSV 文件响应
     */
    @GetMapping("/{batchId}/export")
    public ResponseEntity<byte[]> exportDetails(
            @PathVariable Long batchId,
            @RequestParam(value = "scope", defaultValue = "all") String scope) {
        String csv = service.exportDetailsCsv(batchId, scope);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDisposition(
                org.springframework.http.ContentDisposition.attachment()
                        .filename("account-import-" + batchId + ".csv", java.nio.charset.StandardCharsets.UTF_8)
                        .build());

        return ResponseEntity.ok().headers(headers).body(body);
    }
}
