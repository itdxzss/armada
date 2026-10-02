package com.armada.task.service.impl;

import com.armada.resource.service.GroupDataPackageAllocationService.Snapshot;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskDirectLinkCreateDTO;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.service.PullTaskLinkMatcher;
import com.armada.task.service.PullTaskLinkProbeService;
import com.armada.task.service.PullTaskMaterialTxtParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 无草稿来源校验与匹配；只产出内存计划，不写任务或占用群链接。 */
@Service
public class PullTaskDirectLinkPlanner {
    private static final int MAX_SOURCES = 50;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_PACKAGE_PHONES = 100_000;
    private final PullTaskMaterialTxtParser parser;
    private final PullTaskGroupExecutionMapper executions;
    private final PullTaskStandardDraftSources sources;

    /** 复用现有 TXT、链接与跨域数据包能力，不调用草稿编排。 */
    public PullTaskDirectLinkPlanner(PullTaskMaterialTxtParser parser,
            PullTaskGroupExecutionMapper executions, PullTaskStandardDraftSources sources) {
        this.parser = parser;
        this.executions = executions;
        this.sources = sources;
    }

    /** 在任务落库前完成全部来源校验；每份有效料子必须能匹配可用群链接。 */
    public List<PlannedRow> plan(PullTaskDirectLinkCreateDTO request, List<MultipartFile> files) {
        validate(request);
        List<Long> ids = request.packageIds() == null ? List.of() : request.packageIds();
        List<MultipartFile> uploads = files == null ? List.of() : files;
        if (ids.size() + uploads.size() < 1 || ids.size() + uploads.size() > MAX_SOURCES) {
            throw invalid("请选择 1-50 份 TXT 料子或数据包");
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)
                || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw invalid("数据包 ID 不合法或重复");
        }
        List<MaterialSource> materials = new ArrayList<>();
        for (MultipartFile file : uploads) {
            materials.add(parse(file));
        }
        if (!ids.isEmpty()) {
            for (Snapshot snapshot : sources.dataPackageSourceService().snapshots(ids, MAX_PACKAGE_PHONES)) {
                if (snapshot.phones().isEmpty()) {
                    throw invalid("数据包「" + snapshot.name() + "」没有未使用号码");
                }
                var members = snapshot.phones().stream().map(p -> new PullTaskMaterialTxtParser.ParsedMember(
                        p.memberSeq(), p.sourceLineNo(), p.phone(), p.adminRequired())).toList();
                materials.add(new MaterialSource(new PullTaskMaterialTxtParser.ParseResult(
                        snapshot.name() + ".txt", members.size(), 0, 0, members, List.of()), snapshot));
            }
        }
        String links = mergedLinks(request);
        Set<String> candidates = PullTaskLinkProbeService.candidateLinks(links);
        Set<String> occupied = candidates.isEmpty() ? Set.of()
                : Set.copyOf(executions.selectOccupiedLinks(List.copyOf(candidates)));
        var probe = sources.probeService().probe(links, occupied);
        List<String> keys = IntStream.range(0, materials.size()).mapToObj(String::valueOf).toList();
        var match = PullTaskLinkMatcher.match(probe.poolLinks(), keys, 1, ThreadLocalRandom.current());
        if (!match.unmatchedFileKeys().isEmpty() || match.pairings().isEmpty()) {
            throw invalid("可用群链接不足：料子 " + materials.size() + " 份，可用链接 " + probe.poolLinks().size() + " 条");
        }
        return match.pairings().stream()
                .map(pair -> row(pair, materials.get(Integer.parseInt(pair.fileKey())), probe)).toList();
    }

    /** 校验直接创建合同，重复提交也必须携带合法请求标识。 */
    public static void validate(PullTaskDirectLinkCreateDTO r) {
        if (r == null || r.requestId() == null || !r.requestId().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid("创建请求 requestId 必须为 UUID");
        }
        if (r.taskName() == null || r.taskName().isBlank() || r.taskName().trim().length() > 128) {
            throw invalid("任务名称长度需在 1-128 字符之间");
        }
        if (r.remark() != null && r.remark().length() > 500) {
            throw invalid("备注不超过 500 字符");
        }
        validateRanges(r);
        validateGroups(r);
    }

    private static void validateRanges(PullTaskDirectLinkCreateDTO r) {
        if (r.autoStart() == null || (r.autoStart() != 0 && r.autoStart() != 1)) {
            throw invalid("自动启动取值只能是 0 或 1");
        }
        if (!positive(r.earlyPullCount()) || !positive(r.earlyPullCallCount()) || !positive(r.pullCountMin())
                || !positive(r.pullCountMax()) || r.pullCountMin() > r.pullCountMax()) {
            throw invalid("拉人数范围或前期拉人参数不合法");
        }
        if (!positive(r.pullerCountPerGroup()) || !positive(r.concurrentGroupCount())
                || r.pullIntervalSeconds() == null || r.pullIntervalSeconds() < 0
                || r.stationCountPerCall() == null || r.stationCountPerCall() < 0) {
            throw invalid("拉群执行数量或间隔不合法");
        }
    }

    private static void validateGroups(PullTaskDirectLinkCreateDTO r) {
        if (r.pullerGroupId() == null || r.pullerGroupId() <= 0) {
            throw invalid("请选择拉手分组");
        }
        if (r.stationCountPerCall() > 0 && (r.stationGroupId() == null || r.stationGroupId() <= 0)) {
            throw invalid("站台数量大于 0 时必须选择站台分组");
        }
        if ((r.groupFolderId() != null && r.groupFolderId() <= 0)
                || (r.pullerFinishGroupId() != null && r.pullerFinishGroupId() <= 0)) {
            throw invalid("分组 ID 不合法");
        }
    }

    private String mergedLinks(PullTaskDirectLinkCreateDTO r) {
        if (r.groupFolderId() == null) {
            return r.linksText() == null ? "" : r.linksText();
        }
        sources.groupFolderService().requireExisting(r.groupFolderId());
        String folder = String.join("\n", sources.groupFolderService().usableLinks(r.groupFolderId()));
        return r.linksText() == null ? folder : folder + "\n" + r.linksText();
    }

    private MaterialSource parse(MultipartFile file) {
        String name = file == null ? null : file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            throw invalid("料子文件只支持 .txt 格式");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw invalid("文件 " + name + " 超过 2MB");
        }
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (content.indexOf('\0') >= 0) {
                throw invalid("文件 " + name + " 不是纯文本");
            }
            var parsed = parser.parse(name, content);
            if (!parsed.hasValidMember()) {
                throw invalid("文件 " + name + " 没有有效号码");
            }
            return new MaterialSource(parsed, null);
        } catch (IOException e) {
            throw invalid("文件 " + name + " 读取失败");
        }
    }

    private static PlannedRow row(PullTaskLinkMatcher.Pairing pair, MaterialSource source,
            PullTaskLinkProbeService.ProbeResult probe) {
        var parsed = source.parsed();
        var execution = new PullTaskGroupExecution();
        execution.setSeq(pair.seq());
        execution.setNormalizedLink(pair.normalizedLink());
        execution.setInviteCode(pair.normalizedLink().substring(pair.normalizedLink().lastIndexOf('/') + 1));
        probe.lines().stream().filter(line -> pair.normalizedLink().equals(line.normalizedLink())).findFirst()
                .ifPresent(line -> execution.setSourceLinkLineNo(line.lineNo()));
        execution.setSourceFileIndex(pair.seq());
        execution.setSourceFileName(parsed.fileName());
        execution.setTotalLineCount(parsed.totalLineCount());
        execution.setValidMemberCount(parsed.members().size());
        execution.setInvalidLineCount(parsed.invalidLineCount());
        execution.setDuplicateLineCount(parsed.duplicateLineCount());
        List<PullTaskMaterialMember> members = new ArrayList<>();
        for (int i = 0; i < parsed.members().size(); i++) {
            var parsedMember = parsed.members().get(i);
            var member = new PullTaskMaterialMember();
            member.setMemberSeq(parsedMember.memberSeq());
            member.setSourceLineNo(parsedMember.sourceLineNo());
            member.setNormalizedPhone(parsedMember.normalizedPhone());
            member.setAdminRequired(0);
            if (source.snapshot() != null) {
                member.setSourcePackagePhoneId(source.snapshot().phones().get(i).id());
            }
            members.add(member);
        }
        if (source.snapshot() != null) {
            execution.setSourcePackageId(source.snapshot().packageId());
            execution.setSourcePackageGeneration(source.snapshot().generation());
        }
        return new PlannedRow(execution, members);
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
    private record MaterialSource(PullTaskMaterialTxtParser.ParseResult parsed, Snapshot snapshot) { }

    /** 已校验的群、来源与普通料子号码；只由正式创建事务写入。 */
    public record PlannedRow(PullTaskGroupExecution execution, List<PullTaskMaterialMember> members) { }
}
