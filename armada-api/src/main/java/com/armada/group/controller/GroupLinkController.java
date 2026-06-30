package com.armada.group.controller;

import com.armada.group.model.dto.GroupAnnouncementTextCommandDTO;
import com.armada.group.model.dto.GroupDescriptionCommandDTO;
import com.armada.group.model.dto.GroupIdsDTO;
import com.armada.group.model.dto.GroupLinkImportDTO;
import com.armada.group.model.dto.GroupLinkMigrateDTO;
import com.armada.group.model.dto.GroupLinkPreviewDTO;
import com.armada.group.model.dto.GroupLinkProfileDTO;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.dto.GroupPictureCommandDTO;
import com.armada.group.model.dto.GroupSubjectCommandDTO;
import com.armada.group.model.vo.GroupLinkImportResultVO;
import com.armada.group.model.vo.GroupLinkMemberListVO;
import com.armada.group.model.vo.GroupLinkPreviewBatchVO;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.service.FileLinesExtractor;
import com.armada.group.service.GroupLinkImportService;
import com.armada.group.service.GroupLinkService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
     * @param batchName 批次名称(来源文件/批次名称,非必填;留空存 NULL)
     * @param text      手填多行文本(可选)
     * @param file      上传文件 TXT/CSV/Excel(可选)
     * @return 导入汇总结果
     */
    @PostMapping("/import")
    public ApiResponse<GroupLinkImportResultVO> importLinks(
            @RequestParam("labelId") Long labelId,
            @RequestParam(value = "batchName", required = false) String batchName,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        List<String> lines = extractor.extract(file, text);
        String sourceFileName = file == null || file.isEmpty() ? null : file.getOriginalFilename();
        GroupLinkImportResultVO result = importService.importLinks(
                new GroupLinkImportDTO(labelId, batchName, text, lines, sourceFileName));
        return ApiResponse.ok(result);
    }

    /**
     * B2 群组列表主查询;labelId 可选,为空时查询当前租户全量群组列表。
     *
     * @param query 查询条件(labelId/keyword/status/sourceFileName/origin/membershipState/page/pageSize)
     * @return 分页群链接列表
     */
    @GetMapping
    public ApiResponse<PageResult<GroupLinkVO>> list(@ModelAttribute GroupLinkQuery query) {
        return ApiResponse.ok(groupLinkService.listByLabel(query));
    }

    /**
     * 更新群组列表本地资料。
     *
     * @param id  群链接 ID
     * @param dto 本地群名称/备注/头像 URL
     * @return 空响应
     */
    @PatchMapping("/{id}")
    public ApiResponse<Void> updateProfile(@PathVariable Long id, @RequestBody GroupLinkProfileDTO dto) {
        groupLinkService.updateProfile(id, dto);
        return ApiResponse.ok();
    }

    /**
     * 修改 WhatsApp 真实群名称。
     *
     * @param id  群链接 ID
     * @param dto 操作账号与新群名称
     * @return 空响应
     */
    @PostMapping("/{id}/subject")
    public ApiResponse<Void> updateSubject(@PathVariable Long id, @RequestBody GroupSubjectCommandDTO dto) {
        groupLinkService.updateSubject(id, dto);
        return ApiResponse.ok();
    }

    /**
     * 修改 WhatsApp 真实群描述。
     *
     * @param id  群链接 ID
     * @param dto 操作账号与群描述
     * @return 空响应
     */
    @PostMapping("/{id}/description")
    public ApiResponse<Void> updateDescription(@PathVariable Long id, @RequestBody GroupDescriptionCommandDTO dto) {
        groupLinkService.updateDescription(id, dto);
        return ApiResponse.ok();
    }

    /**
     * 修改 WhatsApp 群公告文本。
     *
     * @param id  群链接 ID
     * @param dto 操作账号与公告文本
     * @return 空响应
     */
    @PostMapping("/{id}/announcement-text")
    public ApiResponse<Void> updateAnnouncementText(
            @PathVariable Long id,
            @RequestBody GroupAnnouncementTextCommandDTO dto) {
        groupLinkService.updateAnnouncementText(id, dto);
        return ApiResponse.ok();
    }

    /**
     * 修改 WhatsApp 真实群头像。
     *
     * @param id  群链接 ID
     * @param dto 操作账号与头像 URL/base64
     * @return 空响应
     */
    @PostMapping("/{id}/picture")
    public ApiResponse<Void> updatePicture(@PathVariable Long id, @RequestBody GroupPictureCommandDTO dto) {
        groupLinkService.updatePicture(id, dto);
        return ApiResponse.ok();
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
     * B5 批量实时预览群链接。
     *
     * @param dto 预览请求(accountId + ids)
     * @return 批量预览汇总
     */
    @PostMapping("/batch-preview")
    public ApiResponse<GroupLinkPreviewBatchVO> previewBatch(@RequestBody GroupLinkPreviewDTO dto) {
        return ApiResponse.ok(groupLinkService.previewBatch(dto));
    }

    /**
     * 群组明细实时成员列表。
     *
     * @param id 群链接 ID
     * @return 协议层实时返回的成员列表;成员明细不落库
     */
    @GetMapping("/{id}/members")
    public ApiResponse<GroupLinkMemberListVO> members(@PathVariable Long id) {
        return ApiResponse.ok(groupLinkService.members(id));
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
