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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 群链接导入业务实现。
 *
 * <p>流程:校验 labelId → 建 batch → LineImporter(归一化+批内去重+upsert) → batchInsert detail
 * → updateCounts batch → 返回汇总 VO。</p>
 */
@Service
public class GroupLinkImportServiceImpl implements GroupLinkImportService {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkImportServiceImpl.class);

    /** 导出失败明细时,时间格式化 pattern(Asia/Shanghai)。 */
    private static final DateTimeFormatter EXPORT_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupLinkImportResultVO importLinks(GroupLinkImportDTO dto) {
        validateRequest(dto);

        String joined = String.join("\n", dto.lines());

        // 建导入批次记录(先插后更新计数)
        GroupLinkImportBatch batch = new GroupLinkImportBatch();
        batch.setLabelId(dto.labelId());
        batch.setBatchName(dto.batchName());
        importBatchMapper.insert(batch);

        // 逐行归一化+批内去重+upsert
        List<LineOutcome<String, Persisted>> outcomes = LineImporter.run(
                joined,
                GroupLinkUrls::normalize,
                url -> url,
                url -> persist(dto.labelId(), batch.getId(), url));

        // 映射明细行 + 汇总统计
        List<GroupLinkImportDetail> details = new ArrayList<>(outcomes.size());
        int inserted = 0;
        int adopted = 0;
        int duplicated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (LineOutcome<String, Persisted> o : outcomes) {
            GroupLinkImportDetail d = new GroupLinkImportDetail();
            d.setBatchId(batch.getId());
            d.setLineNo(o.lineNo());
            d.setRawUrl(o.raw());

            if (o.kind() == Kind.FAILED) {
                d.setResult(GroupLinkImportResult.FORMAT_ERROR.code());
                d.setFailReason(o.reason());
                failed++;
                errors.add("第 " + o.lineNo() + " 行：" + o.reason());
            } else if (o.kind() == Kind.DUPLICATE) {
                d.setResult(GroupLinkImportResult.DUPLICATE.code());
                d.setFailReason("批内重复");
                duplicated++;
            } else {
                Persisted p = o.persistResult();
                d.setResult(p.result().code());
                d.setGroupLinkId(p.linkId());
                if (p.result() == GroupLinkImportResult.SUCCESS) {
                    inserted++;
                } else {
                    adopted++;
                }
            }
            details.add(d);
        }

        if (!details.isEmpty()) {
            detailMapper.batchInsert(details);
        }

        // 回写统计计数
        batch.setTotalRows(outcomes.size());
        batch.setInsertedRows(inserted);
        batch.setAdoptedRows(adopted);
        batch.setSkippedRows(duplicated);
        batch.setFailedRows(failed);
        importBatchMapper.updateCounts(batch);

        log.info("群链接导入 labelId={} batchId={} total={} inserted={} adopted={} dup={} failed={}",
                dto.labelId(), batch.getId(), outcomes.size(), inserted, adopted, duplicated, failed);

        return new GroupLinkImportResultVO(batch.getId(), outcomes.size(),
                inserted, adopted, duplicated, failed, errors);
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
        List<GroupLinkImportDetailVoRow> rows = detailMapper.selectFailed(labelId, batchId);
        List<String[]> result = new ArrayList<>(rows.size());
        for (GroupLinkImportDetailVoRow row : rows) {
            String timeStr = row.getCreatedAt() == null
                    ? ""
                    : EXPORT_TIME_FMT.format(row.getCreatedAt().atZone(ZoneId.of("UTC")));
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

    /**
     * upsert 单条归一化 url:
     * <ul>
     *   <li>不存在 → insert group_link → SUCCESS</li>
     *   <li>已存在(含软删) → adoptToLabel(复活+改归属分组) → ADOPTED</li>
     * </ul>
     */
    private Persisted persist(Long labelId, Long batchId, String url) {
        GroupLink existing = groupLinkMapper.selectAnyByUrl(url);
        if (existing == null) {
            GroupLink row = new GroupLink();
            row.setLinkUrl(url);
            row.setLabelId(labelId);
            row.setImportBatchId(batchId);
            groupLinkMapper.insert(row);
            return new Persisted(GroupLinkImportResult.SUCCESS, row.getId());
        }
        groupLinkMapper.adoptToLabel(existing.getId(), labelId, batchId, null);
        return new Persisted(GroupLinkImportResult.ADOPTED, existing.getId());
    }

    private void validateRequest(GroupLinkImportDTO dto) {
        if (dto.labelId() == null || labelMapper.selectById(dto.labelId()) == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "目标分组不存在");
        }
        List<String> lines = dto.lines();
        String joined = lines == null ? "" : String.join("\n", lines);
        if (!StringUtils.hasText(joined)) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接内容与上传文件不可为空");
        }
    }

    /** persist 内部返回值:result(SUCCESS/ADOPTED) + 群链接 ID。 */
    private record Persisted(GroupLinkImportResult result, Long linkId) {}
}
