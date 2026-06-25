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
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import com.armada.group.model.vo.GroupLinkImportResultVO;
import com.armada.group.service.GroupLinkImportService;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

    /** 辅助:建一个 WS 链接分组并返回。 */
    private GroupLinkLabel insertLabel(String name) {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setName(name);
        label.setRegion("测试区域");
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
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.exists()).isEqualTo(0);
        assertThat(result.duplicated()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.batchId()).isNotNull();

        // group_link 两条落库
        GroupLink link1 = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/NewLink001");
        assertThat(link1).isNotNull();
        assertThat(link1.getLabelId()).isEqualTo(label.getId());
        assertThat(link1.getImportBatchId()).isEqualTo(result.batchId());

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
    }

    @Test
    void importLinks_existingActiveUrl_reportsExists_labelUnchanged() {
        GroupLinkLabel labelA = insertLabel("集成测试分组-已存在源");
        GroupLinkLabel labelB = insertLabel("集成测试分组-已存在目标");

        // 先导入到 labelA(活跃)
        importService.importLinks(new GroupLinkImportDTO(
                labelA.getId(), "第一次导入", null,
                List.of("https://chat.whatsapp.com/ExistMe")));

        GroupLink before = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/ExistMe");
        assertThat(before).isNotNull();
        assertThat(before.getLabelId()).isEqualTo(labelA.getId());

        // 再导入到 labelB — 同 url 已活跃存在 → 已存在,不导入、原链接不动
        GroupLinkImportResultVO result = importService.importLinks(new GroupLinkImportDTO(
                labelB.getId(), "第二次导入", null,
                List.of("https://chat.whatsapp.com/ExistMe")));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.exists()).isEqualTo(1);
        assertThat(result.inserted()).isEqualTo(0);

        // label_id 仍是 labelA(未被搬走)
        GroupLink after = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/ExistMe");
        assertThat(after).isNotNull();
        assertThat(after.getLabelId()).isEqualTo(labelA.getId());

        // detail result = EXISTS(code=2)
        GroupLinkImportDetailQuery q = new GroupLinkImportDetailQuery();
        q.setBatchId(result.batchId());
        q.setPage(1);
        q.setPageSize(10);
        List<GroupLinkImportDetailVoRow> details = detailMapper.selectPage(q);
        assertThat(details).hasSize(1);
        assertThat(details.get(0).getResult()).isEqualTo(GroupLinkImportResult.EXISTS.code());
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
        groupLinkMapper.softDeleteByIds(List.of(imported.getId()));

        // 再导入同 url 到 labelB — 软删行应被复活、归到 labelB,记成功
        GroupLinkImportResultVO result = importService.importLinks(new GroupLinkImportDTO(
                labelB.getId(), "第二次导入", null,
                List.of("https://chat.whatsapp.com/ReviveMe")));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.inserted()).isEqualTo(1);  // 复活计入成功
        assertThat(result.exists()).isEqualTo(0);

        // 复活:deletedAt 清空 + label_id 改为 labelB
        GroupLink revived = groupLinkMapper.selectAnyByUrl("chat.whatsapp.com/ReviveMe");
        assertThat(revived).isNotNull();
        assertThat(revived.getDeletedAt()).isNull();
        assertThat(revived.getLabelId()).isEqualTo(labelB.getId());
    }

    @Test
    void importLinks_mixedOutcomes_batchCountsCorrect() {
        GroupLinkLabel label = insertLabel("集成测试分组-混合");

        // 先插一条已存在的
        GroupLink pre = new GroupLink();
        pre.setLinkUrl("chat.whatsapp.com/AlreadyExists");
        pre.setLabelId(label.getId());
        pre.setImportBatchId(null);
        groupLinkMapper.insert(pre);

        GroupLinkImportResultVO result = importService.importLinks(new GroupLinkImportDTO(
                label.getId(), "混合批次", null,
                List.of(
                        "https://chat.whatsapp.com/BrandNew",        // SUCCESS
                        "https://chat.whatsapp.com/AlreadyExists",   // EXISTS(已存在,活跃)
                        "https://chat.whatsapp.com/BrandNew",        // DUPLICATE
                        "not-a-whatsapp-link"                         // FORMAT_ERROR
                )));

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.exists()).isEqualTo(1);
        assertThat(result.duplicated()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);

        // 验 detail 4 行全落库
        GroupLinkImportDetailQuery q = new GroupLinkImportDetailQuery();
        q.setBatchId(result.batchId());
        q.setPage(1);
        q.setPageSize(10);
        assertThat(detailMapper.countByQuery(q)).isEqualTo(4);
    }
}
