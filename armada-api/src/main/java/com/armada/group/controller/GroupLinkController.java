package com.armada.group.controller;

import com.armada.group.model.dto.GroupIdsDTO;
import com.armada.group.model.dto.GroupLinkImportDTO;
import com.armada.group.model.dto.GroupLinkMigrateDTO;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.vo.GroupLinkImportResultVO;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.service.FileLinesExtractor;
import com.armada.group.service.GroupLinkImportService;
import com.armada.group.service.GroupLinkService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 群链接端点(B1-B4)。
 *
 * <p>Controller 只做参数接收、上下文衔接与响应组装,业务规则全部在 Service。</p>
 */
@RestController
@RequestMapping("/api/group-links")
public class GroupLinkController {

    private final GroupLinkService groupLinkService;
    private final GroupLinkImportService importService;
    private final FileLinesExtractor extractor;

    public GroupLinkController(GroupLinkService groupLinkService,
                               GroupLinkImportService importService,
                               FileLinesExtractor extractor) {
        this.groupLinkService = groupLinkService;
        this.importService = importService;
        this.extractor = extractor;
    }

    /**
     * B1 导入群链接(multipart:text 手填 + 文件上传)。
     *
     * @param labelId   目标WS链接分组 ID
     * @param batchName 批次名称
     * @param text      手填多行文本(可选)
     * @param file      上传文件 TXT/CSV/Excel(可选)
     * @return 导入汇总结果
     */
    @PostMapping("/import")
    public ApiResponse<GroupLinkImportResultVO> importLinks(
            @RequestParam("label_id") Long labelId,
            @RequestParam("batch_name") String batchName,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        List<String> lines = extractor.extract(file, text);
        GroupLinkImportResultVO result = importService.importLinks(
                new GroupLinkImportDTO(labelId, batchName, text, lines));
        return ApiResponse.ok(result);
    }

    /**
     * B2 分组下群链接分页列表。
     *
     * @param query 查询条件(labelId/keyword/page/pageSize)
     * @return 分页群链接列表
     */
    @GetMapping
    public ApiResponse<PageResult<GroupLinkVO>> list(@ModelAttribute GroupLinkQuery query) {
        return ApiResponse.ok(groupLinkService.listByLabel(query));
    }

    /**
     * B3 批量迁移群链接到目标分组。
     *
     * @param dto 迁移请求(linkIds + targetLabelId)
     * @return 实际迁移行数
     */
    @PostMapping("/migrate")
    public ApiResponse<Integer> migrate(@RequestBody GroupLinkMigrateDTO dto) {
        return ApiResponse.ok(groupLinkService.migrate(dto.linkIds(), dto.targetLabelId()));
    }

    /**
     * B4 批量软删除群链接。
     *
     * @param request ID 列表
     * @return 实际删除行数
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Integer> batchDelete(@RequestBody GroupIdsDTO request) {
        return ApiResponse.ok(groupLinkService.batchDelete(request.ids()));
    }
}
