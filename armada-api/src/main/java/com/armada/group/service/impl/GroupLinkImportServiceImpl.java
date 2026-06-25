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

/**
 * 群链接导入业务实现。
 *
 * <p>流程:校验 labelId → 建 batch → LineImporter(归一化+批内去重+upsert) → batchInsert detail
 * → updateCounts batch → 返回汇总 VO。</p>
 */
@Service
public class GroupLinkImportServiceImpl implements GroupLinkImportService {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkImportServiceImpl.class);

    /** 存储层时间字段解释时区:数据库存 UTC。 */
    private static final ZoneId ZONE_UTC = ZoneId.of("UTC");

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
     *   <li><b>SUCCESS</b>  新链接插入 group_link;或同 url 之前被软删,复活并归到本分组</li>
     *   <li><b>EXISTS</b>   同 url 已活跃存在(在某分组),不导入、原链接不动(换组走「迁移分组」)</li>
     *   <li><b>DUPLICATE</b> 本批内同一归一化 url 重复出现,跳过</li>
     *   <li><b>FORMAT_ERROR</b> 不是合法 WhatsApp 邀请链接,记为失败行</li>
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
        batch.setBatchName(dto.batchName());
        importBatchMapper.insert(batch);

        // 4) 逐行处理 = 通用骨架 LineImporter.run(文本, 解析器, 去重键, 落库器):
        //    - 解析器 GroupLinkUrls::normalize: 行 → 归一化 url;非法链接抛异常 → 该行 FAILED
        //    - 去重键 url -> url:        归一化后的 url 本身就是批内去重键 → 重复行 DUPLICATE
        //    - 落库器 persist(...):       未失败/未重复的行才执行,做 insert 或收编 → PERSISTED
        //    泛型 LineOutcome<String, Persisted>:String = 解析记录(归一化 url),Persisted = persist 返回值。
        List<LineOutcome<String, Persisted>> outcomes = LineImporter.run(
                joined,
                GroupLinkUrls::normalize,
                url -> url,
                url -> persist(dto.labelId(), batch.getId(), url));

        // 5) 遍历每行产出:① 组装一条明细行 ② 按类别累加四个计数器
        List<GroupLinkImportDetail> details = new ArrayList<>(outcomes.size());
        int inserted = 0;    // SUCCESS:新插入或复活
        int exists = 0;      // EXISTS:同 url 已活跃存在,未导入
        int duplicated = 0;  // DUPLICATE:批内重复
        int failed = 0;      // FORMAT_ERROR:格式错误
        List<String> errors = new ArrayList<>();  // 失败行的人读提示,回给前端展示

        for (LineOutcome<String, Persisted> o : outcomes) {
            GroupLinkImportDetail d = new GroupLinkImportDetail();
            d.setBatchId(batch.getId());
            d.setLineNo(o.lineNo());
            d.setRawUrl(o.raw());

            if (o.kind() == Kind.FAILED) {
                // 解析阶段抛出 ImportLineException 的行:格式错误
                d.setResult(GroupLinkImportResult.FORMAT_ERROR.code());
                d.setFailReason(o.reason());
                failed++;
                errors.add("第 " + o.lineNo() + " 行：" + o.reason());
            } else if (o.kind() == Kind.DUPLICATE) {
                // 同一归一化 url 在本批已出现过
                d.setResult(GroupLinkImportResult.DUPLICATE.code());
                d.setFailReason("批内重复");
                duplicated++;
            } else {
                // PERSISTED:persist 已决定 SUCCESS(新增/复活)或 EXISTS(同 url 已活跃存在)
                Persisted p = o.persistResult();
                d.setResult(p.result().code());
                d.setGroupLinkId(p.linkId());
                if (p.result() == GroupLinkImportResult.SUCCESS) {
                    inserted++;
                } else {
                    // 已存在:明细标失败原因「已存在」,链接未导入、原行不动
                    d.setFailReason("已存在");
                    exists++;
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
        // adopted_rows 列暂存「已存在」计数(收编已废);列名待改 exists_rows,见 GroupLinkImportBatch.adoptedRows 注释 TODO
        batch.setAdoptedRows(exists);
        batch.setSkippedRows(duplicated);
        batch.setFailedRows(failed);
        importBatchMapper.updateCounts(batch);

        log.info("群链接导入 labelId={} batchId={} total={} inserted={} exists={} dup={} failed={}",
                dto.labelId(), batch.getId(), outcomes.size(), inserted, exists, duplicated, failed);

        // 8) 返回汇总 VO
        return new GroupLinkImportResultVO(batch.getId(), outcomes.size(),
                inserted, exists, duplicated, failed, errors);
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
                    : EXPORT_TIME_FMT.format(row.getCreatedAt().atZone(ZONE_UTC));
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
     * upsert 单条归一化 url,按 url 在库内的存在状态三分:
     * <ul>
     *   <li>库里没有 → insert group_link → SUCCESS</li>
     *   <li>已存在且活跃({@code deletedAt==null}) → 不动库,记 EXISTS(已存在);导入不搬已有链接,换组走「迁移分组」</li>
     *   <li>已存在但软删({@code deletedAt!=null}) → adoptToLabel 复活(deleted_at=NULL)+ 改归属本分组 → SUCCESS</li>
     * </ul>
     * 软删行必须复活、不能再插新行:plain 唯一键 {@code (tenant_id, link_url)} 连软删行也占键,
     * 不复活则这条 url 永远导不回来。
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
        if (existing.getDeletedAt() == null) {
            // 活跃的同 url:已存在,不导入、原链接归属不动
            return new Persisted(GroupLinkImportResult.EXISTS, existing.getId());
        }
        // 软删的同 url:复活并归到本次分组
        groupLinkMapper.adoptToLabel(existing.getId(), labelId, batchId, null);
        return new Persisted(GroupLinkImportResult.SUCCESS, existing.getId());
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

    /** persist 内部返回值:result(SUCCESS=新增/复活 / EXISTS=已存在) + 群链接 ID。 */
    private record Persisted(GroupLinkImportResult result, Long linkId) {}
}
