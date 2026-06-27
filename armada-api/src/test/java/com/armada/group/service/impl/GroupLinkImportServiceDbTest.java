package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.mapper.GroupLinkImportBatchMapper;
import com.armada.group.mapper.GroupLinkImportDetailMapper;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.GroupLinkImportResult;
import com.armada.group.model.dto.GroupLinkImportDTO;
import com.armada.group.model.dto.GroupLinkImportDetailQuery;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkImportBatch;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.enums.GroupLinkImportFailReason;
import com.armada.group.model.enums.GroupLinkImportSuccessType;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import com.armada.group.model.vo.GroupLinkImportResultVO;
import com.armada.group.service.GroupLinkImportService;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GroupLinkImportService 真库集成测试:验三表落库、计数回写、upsert-by-url(已存在不动 / 软删复活)。
 * 每个 @Test 在 @Transactional 内执行并回滚,数据互不干扰。
 */
class GroupLinkImportServiceDbTest extends DbTestBase {

    @Autowired
    private GroupLinkImportService importService;

    @Autowired
    private GroupLinkLabelMapper labelMapper;

    @Autowired
    private GroupLinkMapper groupLinkMapper;

    @Autowired
    private GroupLinkImportBatchMapper batchMapper;

    @Autowired
    private GroupLinkImportDetailMapper detailMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 辅助:建一个 WS 链接分组并返回。 */
    private GroupLinkLabel insertLabel(String name) {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setName(name);
        label.setRegion("测试区域");
        long now = System.currentTimeMillis();
        label.setCreatedAt(now);
        label.setUpdatedAt(now);
        labelMapper.insert(label);
        return label;
    }

    // ---- 核心场景 ----

    @Test
    void importLinks_newUrls_writesThreeTables() {
        GroupLinkLabel label = insertLabel("集成测试分组-新增");

        GroupLinkImportResultVO result = importService.importLinks(new GroupLinkImportDTO(
                label.getId(), "测试批次01", null,
                List.of(
                        "https://chat.whatsapp.com/NewLink001",
                        "https://chat.whatsapp.com/NewLink002"
                )));

        // 返回值正确
        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.successRows()).isEqualTo(2);
        assertThat(result.failedRows()).isEqualTo(0);
        assertThat(result.duplicateRows()).isEqualTo(0);
        assertThat(result.formatErrorRows()).isEqualTo(0);
        assertThat(result.batchId()).isNotNull();

        // group_link 两条落库
        GroupLink link1 = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/NewLink001");
        assertThat(link1).isNotNull();
        assertThat(link1.getLabelId()).isEqualTo(label.getId());
        assertThat(link1.getImportBatchId()).isEqualTo(result.batchId());
        assertThat(link1.getOrigin()).isEqualTo(GroupLinkOrigin.IMPORT.code());
        assertThat(link1.getMembershipState()).isEqualTo(GroupMembershipState.TARGET.code());

        // 计数回写 batch
        GroupLinkImportDetailQuery q = new GroupLinkImportDetailQuery();
        q.setBatchId(result.batchId());
        q.setPage(1);
        q.setPageSize(10);
        long detailCount = detailMapper.countByQuery(q);
        assertThat(detailCount).isEqualTo(2);

        // detail result 全为 SUCCESS(code=1)
        List<GroupLinkImportDetailVoRow> details = detailMapper.selectPage(q);
        details.forEach(d -> assertThat(d.getResult()).isEqualTo(GroupLinkImportResult.SUCCESS.code()));
        details.forEach(d -> assertThat(d.getSuccessType()).isEqualTo(GroupLinkImportSuccessType.INSERTED.code()));
    }

    @Test
    void importLinks_existingImportLink_failsAsDuplicateAndDoesNotMoveLabel() {
        GroupLinkLabel labelA = insertLabel("集成测试分组-已存在源");
        GroupLinkLabel labelB = insertLabel("集成测试分组-已存在目标");

        // 先导入到 labelA(活跃)
        importService.importLinks(new GroupLinkImportDTO(
                labelA.getId(), "第一次导入", null,
                List.of("https://chat.whatsapp.com/ExistMe")));

        GroupLink before = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/ExistMe");
        assertThat(before).isNotNull();
        assertThat(before.getLabelId()).isEqualTo(labelA.getId());

        // 再导入到 labelB — 同 url 已在导入链接中存在 → 失败/重复,原链接不动
        GroupLinkImportResultVO result = importService.importLinks(new GroupLinkImportDTO(
                labelB.getId(), "第二次导入", null,
                List.of("https://chat.whatsapp.com/ExistMe")));

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successRows()).isEqualTo(0);
        assertThat(result.failedRows()).isEqualTo(1);
        assertThat(result.duplicateRows()).isEqualTo(1);

        // label_id 仍是 labelA(未被搬走)
        GroupLink after = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/ExistMe");
        assertThat(after).isNotNull();
        assertThat(after.getLabelId()).isEqualTo(labelA.getId());

        // detail result = FAILED(code=2),失败原因=重复,不关联 group_link_id
        GroupLinkImportDetailQuery q = new GroupLinkImportDetailQuery();
        q.setBatchId(result.batchId());
        q.setPage(1);
        q.setPageSize(10);
        List<GroupLinkImportDetailVoRow> details = detailMapper.selectPage(q);
        assertThat(details).hasSize(1);
        assertThat(details.get(0).getResult()).isEqualTo(GroupLinkImportResult.FAILED.code());
        assertThat(details.get(0).getFailReason()).isEqualTo(GroupLinkImportFailReason.DUPLICATE);
        assertThat(detailGroupLinkId(result.batchId(), 1)).isNull();
    }

    @Test
    void importLinks_existingPullTaskLinkWithoutLabel_adoptsIntoImportGroup() {
        GroupLinkLabel label = insertLabel("集成测试分组-收编目标");
        GroupLink existing = new GroupLink();
        existing.setLinkUrl("chat.whatsapp.com/AdoptPullTask");
        existing.setLabelId(null);
        existing.setImportBatchId(null);
        existing.setOrigin(GroupLinkOrigin.PULL_TASK.code());
        existing.setMembershipState(GroupMembershipState.TARGET.code());
        long now = System.currentTimeMillis();
        existing.setCreatedAt(now);
        existing.setUpdatedAt(now);
        groupLinkMapper.insert(existing);

        GroupLinkImportResultVO result = importService.importLinks(new GroupLinkImportDTO(
                label.getId(), "收编批次", null,
                List.of("https://chat.whatsapp.com/AdoptPullTask")));

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successRows()).isEqualTo(1);
        assertThat(result.failedRows()).isEqualTo(0);

        GroupLink after = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/AdoptPullTask");
        assertThat(after).isNotNull();
        assertThat(after.getLabelId()).isEqualTo(label.getId());
        assertThat(after.getImportBatchId()).isEqualTo(result.batchId());
        assertThat(after.getOrigin()).isEqualTo(GroupLinkOrigin.PULL_TASK.code());

        GroupLinkImportDetailQuery q = new GroupLinkImportDetailQuery();
        q.setBatchId(result.batchId());
        q.setPage(1);
        q.setPageSize(10);
        List<GroupLinkImportDetailVoRow> details = detailMapper.selectPage(q);
        assertThat(details).hasSize(1);
        assertThat(details.get(0).getResult()).isEqualTo(GroupLinkImportResult.SUCCESS.code());
        assertThat(details.get(0).getSuccessType()).isEqualTo(GroupLinkImportSuccessType.ADOPTED.code());
        assertThat(details.get(0).getExistingOrigin()).isEqualTo(GroupLinkOrigin.PULL_TASK.code());
    }

    @Test
    void importLinks_softDeletedUrl_revivesAsSuccess() {
        GroupLinkLabel labelA = insertLabel("集成测试分组-复活源");
        GroupLinkLabel labelB = insertLabel("集成测试分组-复活目标");

        // 先导入到 labelA,再软删该链接
        importService.importLinks(new GroupLinkImportDTO(
                labelA.getId(), "第一次导入", null,
                List.of("https://chat.whatsapp.com/ReviveMe")));
        GroupLink imported = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/ReviveMe");
        groupLinkMapper.softDeleteByIds(List.of(imported.getId()), System.currentTimeMillis());

        // 再导入同 url 到 labelB — 软删行应被复活、归到 labelB,记成功
        GroupLinkImportResultVO result = importService.importLinks(new GroupLinkImportDTO(
                labelB.getId(), "第二次导入", null,
                List.of("https://chat.whatsapp.com/ReviveMe")));

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successRows()).isEqualTo(1);  // 复活计入新增成功
        assertThat(result.failedRows()).isEqualTo(0);

        // 复活:deletedAt 清空 + label_id 改为 labelB
        GroupLink revived = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/ReviveMe");
        assertThat(revived).isNotNull();
        assertThat(revived.getDeletedAt()).isNull();
        assertThat(revived.getLabelId()).isEqualTo(labelB.getId());
    }

    @Test
    void importLinks_nullBatchName_succeeds() {
        // 批次名称(来源文件/批次名称)非必填:不填(null)也应导入成功,batch_name 列须可空。
        GroupLinkLabel label = insertLabel("集成测试分组-空批次名");

        GroupLinkImportResultVO result = importService.importLinks(new GroupLinkImportDTO(
                label.getId(), null, null,
                List.of("https://chat.whatsapp.com/NullBatchName")));

        assertThat(result.batchId()).isNotNull();
        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successRows()).isEqualTo(1);
    }

    @Test
    void importLinks_mixedOutcomes_batchCountsCorrect() {
        GroupLinkLabel label = insertLabel("集成测试分组-混合");

        // 先插一条已存在的
        GroupLink pre = new GroupLink();
        pre.setLinkUrl("chat.whatsapp.com/AlreadyExists");
        pre.setLabelId(label.getId());
        pre.setImportBatchId(null);
        long now = System.currentTimeMillis();
        pre.setCreatedAt(now);
        pre.setUpdatedAt(now);
        groupLinkMapper.insert(pre);

        GroupLinkImportResultVO result = importService.importLinks(new GroupLinkImportDTO(
                label.getId(), "混合批次", null,
                List.of(
                        "https://chat.whatsapp.com/BrandNew",        // SUCCESS
                        "https://chat.whatsapp.com/AlreadyExists",   // EXISTS(已存在,活跃)
                        "https://chat.whatsapp.com/BrandNew",        // DUPLICATE
                        "not-a-whatsapp-link"                         // FORMAT_ERROR
                )));

        assertThat(result.totalRows()).isEqualTo(4);
        assertThat(result.successRows()).isEqualTo(1);
        assertThat(result.duplicateRows()).isEqualTo(2);
        assertThat(result.failedRows()).isEqualTo(3);
        assertThat(result.formatErrorRows()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);

        // 验 detail 4 行全落库
        GroupLinkImportDetailQuery q = new GroupLinkImportDetailQuery();
        q.setBatchId(result.batchId());
        q.setPage(1);
        q.setPageSize(10);
        assertThat(detailMapper.countByQuery(q)).isEqualTo(4);
    }

    private Long detailGroupLinkId(Long batchId, int lineNo) {
        return jdbc.queryForObject(
                "SELECT group_link_id FROM group_link_import_detail WHERE batch_id = ? AND line_no = ?",
                Long.class,
                batchId,
                lineNo);
    }
}
