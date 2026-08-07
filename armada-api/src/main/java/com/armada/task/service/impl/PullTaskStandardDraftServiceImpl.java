package com.armada.task.service.impl;

import com.armada.group.service.GroupFolderService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.model.vo.PullTaskStandardExecutionRowVO;
import com.armada.task.model.vo.PullTaskStandardFileResultVO;
import com.armada.task.model.vo.PullTaskStandardLinkLineVO;
import com.armada.task.model.vo.PullTaskStandardMaterialLineErrorVO;
import com.armada.task.service.PullTaskLinkMatcher;
import com.armada.task.service.PullTaskLinkMatcher.MatchResult;
import com.armada.task.service.PullTaskLinkMatcher.Pairing;
import com.armada.task.service.PullTaskLinkProbeService;
import com.armada.task.service.PullTaskLinkProbeService.LinkLine;
import com.armada.task.service.PullTaskLinkProbeService.ProbeResult;
import com.armada.task.service.PullTaskMaterialTxtParser;
import com.armada.task.service.PullTaskMaterialTxtParser.ParseResult;
import com.armada.task.service.PullTaskMaterialTxtParser.ParsedMember;
import com.armada.task.service.PullTaskStandardDraftService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 普通群链接创建页草稿编排实现。
 *
 * <p>本类<b>刻意不标 {@code @Transactional}</b>：匹配追加流程里包含最坏 40 秒的公开邀请页
 * 预检，事务包住外部 HTTP 会让数据库连接被网络阻塞占用。全部写操作委托给
 * {@link PullTaskStandardDraftWriter}，事务边界落在那个 bean 上。</p>
 */
@Service
public class PullTaskStandardDraftServiceImpl implements PullTaskStandardDraftService {

    private static final Logger log = LoggerFactory.getLogger(PullTaskStandardDraftServiceImpl.class);

    /** 用户还没有草稿时返回的空视图。 */
    private static final PullTaskStandardDraftVO EMPTY_VIEW = new PullTaskStandardDraftVO(
            null, List.of(), List.of(), List.of(), 0, 0, 0);

    /** 单次上传允许的最大文件数。 */
    private static final int MAX_FILE_COUNT = 50;

    /** 单个文件允许的最大字节数。 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /** 唯一接受的料子文件扩展名。 */
    private static final String TXT_SUFFIX = ".txt";

    /** 零有效号码文件的拒绝原因。 */
    private static final String ZERO_VALID_REASON = "文件内没有有效号码，请修正后重新上传";

    private final PullTaskMapper pullTaskMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskStandardDraftWriter writer;
    private final PullTaskMaterialTxtParser txtParser;
    private final PullTaskLinkProbeService probeService;
    private final GroupFolderService groupFolderService;

    /**
     * 创建草稿编排服务。
     *
     * @param pullTaskMapper  任务主表数据访问
     * @param executionMapper 执行行数据访问
     * @param writer          草稿事务写入组件
     * @param txtParser       TXT 料子解析器
     * @param probeService    链接判定服务
     * @param groupFolderService 群组运营分组服务
     */
    public PullTaskStandardDraftServiceImpl(PullTaskMapper pullTaskMapper,
                                            PullTaskGroupExecutionMapper executionMapper,
                                            PullTaskStandardDraftWriter writer,
                                            PullTaskMaterialTxtParser txtParser,
                                            PullTaskLinkProbeService probeService,
                                            GroupFolderService groupFolderService) {
        this.pullTaskMapper = pullTaskMapper;
        this.executionMapper = executionMapper;
        this.writer = writer;
        this.txtParser = txtParser;
        this.probeService = probeService;
        this.groupFolderService = groupFolderService;
    }

    @Override
    public PullTaskStandardDraftVO plan(Long groupFolderId, String linksText,
                                        List<MultipartFile> files,
                                        long userId, String operatorName) {
        String mergedLinksText = mergeSourceLinks(groupFolderId, linksText);

        // 1. 上传校验与解析：纯 CPU，事务外。
        List<ParsedUpload> uploads = parseUploads(files);

        long now = System.currentTimeMillis();
        PullTask draft = writer.ensureDraft(userId, operatorName, now);
        List<PullTaskGroupExecution> existingRows = executionMapper.selectByTaskId(draft.getId());

        // 2. 归一化与占用查询：只做本地计划，不在创建阶段访问 WhatsApp。
        ProbeResult probe = probeLinks(mergedLinksText);

        Set<String> usedLinks = new LinkedHashSet<>();
        int maxSeq = 0;
        for (PullTaskGroupExecution row : existingRows) {
            usedLinks.add(row.getNormalizedLink());
            maxSeq = Math.max(maxSeq, row.getSeq());
        }
        List<String> remainingLinks = probe.poolLinks().stream()
                .filter(link -> !usedLinks.contains(link))
                .toList();
        List<ParsedUpload> accepted = uploads.stream().filter(ParsedUpload::accepted).toList();

        // 3. 不放回随机匹配，已成行的链接不参与，因此已有执行行不会被扰动。
        MatchResult match = PullTaskLinkMatcher.match(
                remainingLinks, fileKeys(accepted.size()), maxSeq + 1, ThreadLocalRandom.current());
        writer.append(draft.getId(), toAppendRows(match, accepted, lineNoByLink(probe)), now);

        log.info("创建页追加执行行 taskId={} matched={} remainingLinks={} ignoredFiles={} operatorId={}",
                draft.getId(), match.pairings().size(), match.unmatchedLinks().size(),
                match.unmatchedFileKeys().size(), userId);
        return toView(draft, toLinkLineViews(probe), toFileResultViews(uploads),
                match.unmatchedLinks().size(), match.unmatchedFileKeys().size());
    }

    /**
     * 合并运营分组内的可用链接与本次手工粘贴链接。
     *
     * <p>分组存在性和租户隔离由群组域 Service 保证；后续统一交给链接判定服务去重，
     * 避免任务域直接依赖群组域 Mapper 或实体。</p>
     *
     * @param groupFolderId 运营分组 ID，可为空
     * @param linksText     手工粘贴文本，可为空
     * @return 可交给链接判定服务的合并文本
     */
    private String mergeSourceLinks(Long groupFolderId, String linksText) {
        if (groupFolderId == null) {
            return linksText;
        }
        List<String> folderLinks = groupFolderService.usableLinks(groupFolderId);
        if (folderLinks.isEmpty()) {
            return linksText;
        }
        String folderText = String.join("\n", folderLinks);
        return linksText == null || linksText.isBlank()
                ? folderText
                : folderText + "\n" + linksText;
    }

    /**
     * 校验并解析本次上传的全部 TXT。
     *
     * @param files 上传文件；null 或空返回空列表
     * @return 逐文件解析结果
     * @throws BusinessException 文件数超限，或任一文件扩展名、大小、内容不合格时
     */
    private List<ParsedUpload> parseUploads(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (files.size() > MAX_FILE_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "单次最多上传 " + MAX_FILE_COUNT + " 个料子文件");
        }
        List<ParsedUpload> uploads = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            uploads.add(parseUpload(file));
        }
        return uploads;
    }

    /**
     * 校验并解析单个 TXT。
     *
     * @param file 上传文件
     * @return 解析结果；零有效号码时带拒绝原因
     * @throws BusinessException 扩展名、大小或内容不合格时
     */
    private ParsedUpload parseUpload(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(TXT_SUFFIX)) {
            throw new BusinessException(ErrorCode.VALIDATION, "料子文件只支持 .txt 格式");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "文件 " + fileName + " 超过 2MB，请拆分后重新上传");
        }
        ParseResult parsed = txtParser.parse(fileName, readUtf8(file, fileName));
        return new ParsedUpload(parsed, parsed.hasValidMember() ? null : ZERO_VALID_REASON);
    }

    /**
     * 按 UTF-8 读出文件内容，并做二进制内容嗅探。
     *
     * <p>只看扩展名挡不住把二进制文件改名成 .txt 上传，用 NUL 字节做最轻量的嗅探。</p>
     *
     * @param file     上传文件
     * @param fileName 原始文件名，用于错误提示
     * @return 文件全文
     * @throws BusinessException 读取失败或内容不是文本时
     */
    private static String readUtf8(MultipartFile file, String fileName) {
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "文件 " + fileName + " 读取失败");
        }
        if (content.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "文件 " + fileName + " 不是纯文本，请上传 UTF-8 编码的 .txt");
        }
        return content;
    }

    /**
     * 查询归一化链接占用，并生成本地计划。
     *
     * @param linksText 链接框全量文本
     * @return 逐行判定与匹配池
     */
    private ProbeResult probeLinks(String linksText) {
        Set<String> candidates = PullTaskLinkProbeService.candidateLinks(linksText);
        // foreach 遇空集合会生成非法 SQL，必须在这里判空。
        Set<String> occupied = candidates.isEmpty()
                ? Set.of()
                : Set.copyOf(executionMapper.selectOccupiedLinks(List.copyOf(candidates)));
        return probeService.probe(linksText, occupied);
    }

    /**
     * 生成匹配器用的文件键：已接受文件在列表中的下标。
     *
     * @param acceptedCount 已接受文件数
     * @return 下标字符串列表
     */
    private static List<String> fileKeys(int acceptedCount) {
        List<String> keys = new ArrayList<>(acceptedCount);
        for (int index = 0; index < acceptedCount; index++) {
            keys.add(String.valueOf(index));
        }
        return keys;
    }

    /**
     * 取每条归一化链接首次出现的行号。
     *
     * @param probe 逐行判定结果
     * @return 链接到原始行号的映射
     */
    private static Map<String, Integer> lineNoByLink(ProbeResult probe) {
        Map<String, Integer> lineNos = new LinkedHashMap<>();
        for (LinkLine line : probe.lines()) {
            if (line.normalizedLink() != null) {
                lineNos.putIfAbsent(line.normalizedLink(), line.lineNo());
            }
        }
        return lineNos;
    }

    /**
     * 把匹配结果转成待写入的执行行与料子。
     *
     * @param match        匹配结果
     * @param accepted     已接受的文件，下标与 fileKey 对应
     * @param lineNoByLink 链接到原始行号的映射
     * @return 待写入批次
     */
    private static List<PullTaskStandardDraftWriter.AppendRow> toAppendRows(
            MatchResult match, List<ParsedUpload> accepted, Map<String, Integer> lineNoByLink) {
        List<PullTaskStandardDraftWriter.AppendRow> rows = new ArrayList<>(match.pairings().size());
        for (Pairing pairing : match.pairings()) {
            ParseResult parsed = accepted.get(Integer.parseInt(pairing.fileKey())).parsed();
            rows.add(new PullTaskStandardDraftWriter.AppendRow(
                    toExecution(pairing, parsed, lineNoByLink), toMembers(parsed.members())));
        }
        return rows;
    }

    /**
     * 组装一条草稿执行行。
     *
     * @param pairing      配对结果
     * @param parsed       配对到的 TXT 解析结果
     * @param lineNoByLink 链接到原始行号的映射
     * @return 执行行实体；taskId 与时间戳由写入组件补齐
     */
    private static PullTaskGroupExecution toExecution(Pairing pairing, ParseResult parsed,
                                                      Map<String, Integer> lineNoByLink) {
        String link = pairing.normalizedLink();
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setSeq(pairing.seq());
        execution.setNormalizedLink(link);
        execution.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        execution.setSourceLinkLineNo(lineNoByLink.get(link));
        // source_file_index 在任务内唯一。增量上传没有全局上传序号可用，
        // 取与 seq 相同的值，语义是"第几个进入计划的文件"。
        execution.setSourceFileIndex(pairing.seq());
        execution.setSourceFileName(parsed.fileName());
        execution.setTotalLineCount(parsed.totalLineCount());
        execution.setValidMemberCount(parsed.members().size());
        execution.setInvalidLineCount(parsed.invalidLineCount());
        execution.setDuplicateLineCount(parsed.duplicateLineCount());
        return execution;
    }

    /**
     * 解析结果转料子成员实体。
     *
     * @param parsedMembers 去重后的号码
     * @return 料子成员实体；执行行 ID 与时间戳由写入组件补齐
     */
    private static List<PullTaskMaterialMember> toMembers(List<ParsedMember> parsedMembers) {
        List<PullTaskMaterialMember> members = new ArrayList<>(parsedMembers.size());
        for (ParsedMember parsed : parsedMembers) {
            PullTaskMaterialMember member = new PullTaskMaterialMember();
            member.setMemberSeq(parsed.memberSeq());
            member.setSourceLineNo(parsed.sourceLineNo());
            member.setNormalizedPhone(parsed.normalizedPhone());
            member.setAdminRequired(parsed.adminRequired() ? 1 : 0);
            members.add(member);
        }
        return members;
    }

    /**
     * 链接逐行判定转出参。
     *
     * @param probe 判定结果
     * @return 逐行出参
     */
    private static List<PullTaskStandardLinkLineVO> toLinkLineViews(ProbeResult probe) {
        return probe.lines().stream()
                .map(line -> new PullTaskStandardLinkLineVO(line.lineNo(), line.raw(),
                        line.normalizedLink(), line.status(), line.reason()))
                .toList();
    }

    /**
     * 逐文件解析结果转出参。
     *
     * @param uploads 本次上传的解析结果
     * @return 逐文件出参
     */
    private static List<PullTaskStandardFileResultVO> toFileResultViews(List<ParsedUpload> uploads) {
        return uploads.stream().map(upload -> {
            ParseResult parsed = upload.parsed();
            return new PullTaskStandardFileResultVO(parsed.fileName(), upload.accepted(),
                    parsed.members().size(), parsed.invalidLineCount(),
                    parsed.duplicateLineCount(), upload.rejectReason(),
                    parsed.errors().stream()
                            .map(error -> new PullTaskStandardMaterialLineErrorVO(
                                    error.lineNo(), error.reason()))
                            .toList());
        }).toList();
    }

    /**
     * 单个上传文件的解析结果与是否进入匹配池。
     *
     * @param parsed       TXT 解析结果
     * @param rejectReason 未进入匹配池的原因；进入时为 null
     */
    private record ParsedUpload(ParseResult parsed, String rejectReason) {

        /** 是否进入随机匹配池。 */
        private boolean accepted() {
            return rejectReason == null;
        }
    }

    @Override
    public PullTaskStandardDraftVO current(long userId) {
        PullTask draft = pullTaskMapper.selectLatestDraftByCreator(userId);
        if (draft == null) {
            return EMPTY_VIEW;
        }
        return toView(draft, List.of(), List.of(), 0, 0);
    }

    @Override
    public PullTaskStandardDraftVO removeRow(long rowId, long userId) {
        PullTask draft = requireDraft(userId);
        writer.removeRow(draft.getId(), rowId);
        log.info("创建页移除执行行 taskId={} rowId={} operatorId={}", draft.getId(), rowId, userId);
        return toView(draft, List.of(), List.of(), 0, 0);
    }

    @Override
    public PullTaskStandardDraftVO clear(long userId) {
        PullTask draft = requireDraft(userId);
        writer.clearAll(draft.getId());
        log.info("创建页清除全部执行行 taskId={} operatorId={}", draft.getId(), userId);
        return toView(draft, List.of(), List.of(), 0, 0);
    }

    /**
     * 取当前用户的草稿，没有则拒绝操作。
     *
     * @param userId 当前登录用户 ID
     * @return 草稿任务行
     * @throws BusinessException 草稿不存在时
     */
    private PullTask requireDraft(long userId) {
        PullTask draft = pullTaskMapper.selectLatestDraftByCreator(userId);
        if (draft == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "当前没有可编辑的创建页草稿");
        }
        return draft;
    }

    /**
     * 读回执行行并组装草稿视图。
     *
     * @param draft              草稿任务行
     * @param linkLines          本次请求的链接逐行结果；回读与编辑场景为空
     * @param fileResults        本次请求的逐文件结果；回读与编辑场景为空
     * @param remainingLinkCount 本次请求后仍未匹配的有效链接数
     * @param ignoredFileCount   本次被忽略的文件数
     * @return 草稿视图
     */
    PullTaskStandardDraftVO toView(PullTask draft,
                                   List<PullTaskStandardLinkLineVO> linkLines,
                                   List<PullTaskStandardFileResultVO> fileResults,
                                   int remainingLinkCount,
                                   int ignoredFileCount) {
        List<PullTaskStandardExecutionRowVO> rows = executionMapper.selectByTaskId(draft.getId())
                .stream()
                .map(PullTaskStandardDraftServiceImpl::toRowView)
                .toList();
        return new PullTaskStandardDraftVO(draft.getId(), rows,
                linkLines, fileResults, rows.size(), remainingLinkCount, ignoredFileCount);
    }

    /**
     * 执行行实体转出参。
     *
     * @param row 执行行实体
     * @return 执行行出参
     */
    private static PullTaskStandardExecutionRowVO toRowView(PullTaskGroupExecution row) {
        return new PullTaskStandardExecutionRowVO(row.getId(), row.getSeq(),
                row.getNormalizedLink(), row.getSourceLinkLineNo(), row.getSourceFileName(),
                row.getTotalLineCount(), row.getValidMemberCount(),
                row.getInvalidLineCount(), row.getDuplicateLineCount());
    }
}
