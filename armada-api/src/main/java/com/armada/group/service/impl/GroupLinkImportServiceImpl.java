package com.armada.group.service.impl;

import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupLinkImportBatchMapper;
import com.armada.group.mapper.GroupLinkImportDetailMapper;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.GroupLinkImportResult;
import com.armada.group.model.dto.GroupLinkImportDTO;
import com.armada.group.model.dto.GroupLinkImportDetailQuery;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkImportBatch;
import com.armada.group.model.entity.GroupLinkImportDetail;
import com.armada.group.model.enums.GroupLinkImportFailReason;
import com.armada.group.model.enums.GroupLinkImportSuccessType;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.group.model.vo.GroupLinkImportDetailVO;
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import com.armada.group.model.vo.GroupLinkImportResultVO;
import com.armada.group.service.GroupLinkImportService;
import com.armada.group.service.GroupLinkUrls;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.util.LineImporter;
import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 群链接导入业务实现。
 *
 * <p>流程:校验 labelId → 建 batch → LineImporter(归一化+批内去重+upsert) → batchInsert detail
 * → updateCounts batch → 返回汇总 VO。</p>
 */
@Service
public class GroupLinkImportServiceImpl implements GroupLinkImportService {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkImportServiceImpl.class);

    /** 导出输出时区:上海时间。 */
    private static final ZoneId ZONE_CN = ZoneId.of("Asia/Shanghai");

    /** 导出失败明细时,时间格式化 pattern(Asia/Shanghai)。 */
    private static final DateTimeFormatter EXPORT_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE_CN);

    private final GroupLinkLabelMapper labelMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkImportBatchMapper importBatchMapper;
    private final GroupLinkImportDetailMapper detailMapper;
    private final GroupConverter converter;

    public GroupLinkImportServiceImpl(GroupLinkLabelMapper labelMapper,
                                      GroupLinkMapper groupLinkMapper,
                                      GroupLinkImportBatchMapper importBatchMapper,
                                      GroupLinkImportDetailMapper detailMapper,
                                      GroupConverter converter) {
        this.labelMapper = labelMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.importBatchMapper = importBatchMapper;
        this.detailMapper = detailMapper;
        this.converter = converter;
    }

    /**
     * 把一批群邀请链接文本导入到某个分组(labelId),整体一个事务:批次头 + 全部明细 + group_link
     * 落库要么一起提交、要么一起回滚({@code rollbackFor = Exception.class})。
     *
     * <p>每行最终落到以下互斥类别之一:
     * <ul>
     *   <li><b>成功/新增</b> 新链接插入 group_link;或同 url 之前被软删,复活并归到本分组</li>
     *   <li><b>成功/收编</b> 同 url 已由拉群/进群/自建入口进入群组池,且尚未归入导入分组</li>
     *   <li><b>失败/重复</b> 本批内同一归一化 url 重复出现;或已在导入链接中重复导入</li>
     *   <li><b>失败/格式错误</b> 不是合法 WhatsApp 邀请链接</li>
     * </ul>
     * 空行不计入任何统计(被 {@link LineImporter} 跳过)。</p>
     *
     * @param dto 含目标分组 labelId、批次名、以及已由 Controller 解析好的行列表 lines
     * @return 汇总 VO:批次 id + 各类别计数 + 失败行的人读错误信息列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupLinkImportResultVO importLinks(GroupLinkImportDTO dto) {
        // 1) 校验:labelId 必须存在的分组,lines 非空(否则抛 VALIDATION)
        validateRequest(dto);

        // 2) dto.lines() 已是行列表;LineImporter 的入参是单段文本,这里用换行重新拼回,
        //    交给 LineImporter 内部再按 \R 拆行(它的契约是"文本进")。
        String joined = String.join("\n", dto.lines());

        // 3) 先插批次头拿到自增 id(计数列留到末尾再 updateCounts 回写)
        GroupLinkImportBatch batch = new GroupLinkImportBatch();
        batch.setLabelId(dto.labelId());
        // 批次名称(来源文件/批次名称)非必填:留空(null/空白)统一存 NULL,不存空白串
        batch.setBatchName(blankToNull(dto.batchName()));
        batch.setCreatedAt(System.currentTimeMillis());
        importBatchMapper.insert(batch);

        // 4) 逐行处理 = 通用骨架 LineImporter.run(文本, 解析器, 去重键, 落库器):
        //    - 解析器 GroupLinkUrls::normalize: 行 → 归一化 url;非法链接抛异常 → 该行 FAILED
        //    - 去重键 url -> url:        归一化后的 url 本身就是批内去重键 → 重复行 DUPLICATE
        //    - 落库器 persist(...):       未失败/未重复的行才执行,做 insert/复活 或判「已存在」 → PERSISTED
        //    泛型 LineOutcome<String, Persisted>:String = 解析记录(归一化 url),Persisted = persist 返回值。
        List<LineOutcome<String, Persisted>> outcomes = LineImporter.run(
                joined,
                GroupLinkUrls::normalize,
                url -> url,
                url -> persist(dto.labelId(), batch.getId(), url));

        // 5) 遍历每行产出:① 组装一条明细行 ② 按类别累加四个计数器
        List<GroupLinkImportDetail> details = new ArrayList<>(outcomes.size());
        int inserted = 0;
        int adopted = 0;
        int duplicate = 0;
        int formatError = 0;
        List<String> errors = new ArrayList<>();  // 失败行的人读提示,回给前端展示

        for (LineOutcome<String, Persisted> o : outcomes) {
            GroupLinkImportDetail d = new GroupLinkImportDetail();
            d.setBatchId(batch.getId());
            d.setLineNo(o.lineNo());
            d.setRawUrl(o.raw());
            d.setCreatedAt(System.currentTimeMillis());

            if (o.kind() == Kind.FAILED) {
                // 解析阶段抛出 ImportLineException 的行:格式错误
                d.setResult(GroupLinkImportResult.FAILED.code());
                d.setFailReason(GroupLinkImportFailReason.FORMAT_ERROR);
                formatError++;
                errors.add("第 " + o.lineNo() + " 行：" + o.reason());
            } else if (o.kind() == Kind.DUPLICATE) {
                // 同一归一化 url 在本批已出现过
                d.setResult(GroupLinkImportResult.FAILED.code());
                d.setFailReason(GroupLinkImportFailReason.DUPLICATE);
                duplicate++;
            } else {
                // PERSISTED:persist 已决定成功(新增/收编)或失败(导入域内重复)
                Persisted p = o.persistResult();
                d.setResult(p.result().code());
                d.setGroupLinkId(p.linkId());
                d.setSuccessType(p.successType());
                d.setFailReason(p.failReason());
                d.setExistingOrigin(p.existingOrigin());
                if (p.result() == GroupLinkImportResult.SUCCESS) {
                    if (GroupLinkImportSuccessType.ADOPTED.code() == p.successType()) {
                        adopted++;
                    } else {
                        inserted++;
                    }
                } else {
                    duplicate++;
                }
            }
            details.add(d);
        }

        // 6) 一次性批量插明细行
        if (!details.isEmpty()) {
            detailMapper.batchInsert(details);
        }

        // 7) 把统计计数回写到批次头(totalRows 只含非空行,空行已被 LineImporter 跳过)
        batch.setTotalRows(outcomes.size());
        batch.setInsertedRows(inserted);
        batch.setAdoptedRows(adopted);
        batch.setDuplicateRows(duplicate);
        batch.setFailedRows(duplicate + formatError);
        importBatchMapper.updateCounts(batch);

        log.info("群链接导入 labelId={} batchId={} total={} inserted={} adopted={} duplicate={} formatError={} failed={}",
                dto.labelId(), batch.getId(), outcomes.size(), inserted, adopted, duplicate, formatError,
                duplicate + formatError);

        // 8) 返回汇总 VO
        return new GroupLinkImportResultVO(
                batch.getId(),
                outcomes.size(),
                inserted + adopted,
                duplicate + formatError,
                duplicate,
                formatError,
                errors);
    }

    @Override
    public PageResult<GroupLinkImportDetailVO> listDetails(GroupLinkImportDetailQuery query) {
        long total = detailMapper.countByQuery(query);
        List<GroupLinkImportDetailVO> list = total == 0
                ? List.of()
                : converter.toImportDetailVOList(detailMapper.selectPage(query));
        return PageResult.of(list, query.getPage(), query.getPageSize(), total);
    }

    @Override
    public List<String[]> exportFailed(Long labelId, Long batchId) {
        if (labelId == null && batchId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "labelId 与 batchId 至少提供一个");
        }
        List<GroupLinkImportDetailVoRow> rows = detailMapper.selectFailed(labelId, batchId);
        List<String[]> result = new ArrayList<>(rows.size());
        for (GroupLinkImportDetailVoRow row : rows) {
            String timeStr = row.getCreatedAt() == null
                    ? ""
                    : EXPORT_TIME_FMT.format(Instant.ofEpochMilli(row.getCreatedAt()));
            result.add(new String[]{
                    String.valueOf(row.getLineNo()),
                    nullToEmpty(row.getGroupName()),
                    nullToEmpty(row.getRawUrl()),
                    nullToEmpty(row.getFailReason()),
                    timeStr
            });
        }
        return result;
    }

    /** null → 空字符串(CSV 字段禁返 null)。 */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 空/空白 → null(批次名非必填,留空存 NULL 而非空白串)。 */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * upsert 单条归一化 url,按 url 在库内的存在状态四分:
     * <ul>
     *   <li>库里没有 → insert group_link → 成功/新增</li>
     *   <li>已存在但软删({@code deletedAt!=null}) → 复活并归到本分组 → 成功/新增</li>
     *   <li>已存在且活跃但 {@code labelId==null} → 收编到本分组 → 成功/收编</li>
     *   <li>已存在且活跃并已有 {@code labelId} → 不动库 → 失败/重复</li>
     * </ul>
     * 软删行必须复活、不能再插新行:plain 唯一键 {@code (tenant_id, link_url)} 连软删行也占键,
     * 不复活则这条 url 永远导不回来。
     */
    private Persisted persist(Long labelId, Long batchId, String url) {
        GroupLink existing = groupLinkMapper.selectAnyByUrl(url);
        long now = System.currentTimeMillis();
        if (existing == null) {
            GroupLink row = new GroupLink();
            row.setLinkUrl(url);
            row.setLabelId(labelId);
            row.setImportBatchId(batchId);
            row.setOrigin(GroupLinkOrigin.IMPORT.code());
            row.setMembershipState(GroupMembershipState.TARGET.code());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            groupLinkMapper.insert(row);
            return new Persisted(GroupLinkImportResult.SUCCESS,
                    GroupLinkImportSuccessType.INSERTED.code(), null, row.getId(), null);
        }
        if (existing.getDeletedAt() != null) {
            groupLinkMapper.adoptToLabel(existing.getId(), labelId, batchId, null, now);
            return new Persisted(GroupLinkImportResult.SUCCESS,
                    GroupLinkImportSuccessType.INSERTED.code(), null, existing.getId(), null);
        }
        if (existing.getLabelId() == null) {
            int updated = groupLinkMapper.adoptActiveIntoImport(existing.getId(), labelId, batchId, now);
            if (updated == 0) {
                return new Persisted(GroupLinkImportResult.FAILED, null,
                        GroupLinkImportFailReason.DUPLICATE, null, null);
            }
            return new Persisted(GroupLinkImportResult.SUCCESS,
                    GroupLinkImportSuccessType.ADOPTED.code(), null, existing.getId(), existing.getOrigin());
        }
        return new Persisted(GroupLinkImportResult.FAILED, null,
                GroupLinkImportFailReason.DUPLICATE, null, null);
    }

    private void validateRequest(GroupLinkImportDTO dto) {
        if (dto.labelId() == null || labelMapper.selectById(dto.labelId()) == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "目标分组不存在");
        }
        // 只做 null/empty 检查,不做 join(join 在 importLinks 调用方处理,避免重复计算)
        List<String> lines = dto.lines();
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接内容与上传文件不可为空");
        }
    }

    /** persist 内部返回值:主结果 + 成功类型/失败原因 + 群链接 ID + 收编前来源。 */
    private record Persisted(GroupLinkImportResult result,
                             Integer successType,
                             String failReason,
                             Long linkId,
                             Integer existingOrigin) {
    }
}
