package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.model.dto.GroupLinkImportDetailQuery;
import com.armada.group.model.entity.GroupLinkImportBatch;
import com.armada.group.model.entity.GroupLinkImportDetail;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * GroupLinkImportDetailMapper 真库测试:验批量插入/分页 JOIN/失败筛选。
 * 每个 @Test 在 @Transactional 内运行并回滚,数据互不干扰。
 */
class GroupLinkImportDetailMapperDbTest extends DbTestBase {

    @Autowired
    private GroupLinkImportDetailMapper detailMapper;

    @Autowired
    private GroupLinkImportBatchMapper batchMapper;

    @Autowired
    private GroupLinkLabelMapper labelMapper;

    // ---- 辅助方法 ----

    private GroupLinkLabel insertLabel(String name) {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setName(name);
        label.setRegion("印度");
        label.setRemark("测试");
        labelMapper.insert(label);
        return label;
    }

    private GroupLinkImportBatch insertBatch(Long labelId, String fileName) {
        GroupLinkImportBatch batch = new GroupLinkImportBatch();
        batch.setLabelId(labelId);
        batch.setBatchName("测试批次");
        batch.setSourceFileName(fileName);
        batchMapper.insert(batch);
        return batch;
    }

    private GroupLinkImportDetail buildDetail(Long batchId, int lineNo, String url, int result, String failReason) {
        GroupLinkImportDetail detail = new GroupLinkImportDetail();
        detail.setBatchId(batchId);
        detail.setLineNo(lineNo);
        detail.setRawUrl(url);
        detail.setGroupName("测试群");
        detail.setResult(result);
        detail.setFailReason(failReason);
        return detail;
    }

    // ---- 测试 ----

    @Test
    void batchInsert_andSelectPage_joinsSourceFileName() {
        GroupLinkLabel label = insertLabel("明细分页测试分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "detail_test.xlsx");

        List<GroupLinkImportDetail> details = List.of(
                buildDetail(batch.getId(), 1, "chat.whatsapp.com/Detail1", 1, null),   // SUCCESS
                buildDetail(batch.getId(), 2, "chat.whatsapp.com/Detail2", 2, null),   // ADOPTED
                buildDetail(batch.getId(), 3, "chat.whatsapp.com/Detail3", 3, "批内重复") // DUPLICATE
        );
        int inserted = detailMapper.batchInsert(details);
        assertThat(inserted).isEqualTo(3);

        GroupLinkImportDetailQuery query = new GroupLinkImportDetailQuery();
        query.setBatchId(batch.getId());
        query.setPage(1);
        query.setPageSize(10);

        long total = detailMapper.countByQuery(query);
        assertThat(total).isEqualTo(3);

        List<GroupLinkImportDetailVoRow> rows = detailMapper.selectPage(query);
        assertThat(rows).hasSize(3);
        // JOIN 出 sourceFileName
        rows.forEach(r -> assertThat(r.getSourceFileName()).isEqualTo("detail_test.xlsx"));
        // 按行号升序
        assertThat(rows.get(0).getLineNo()).isEqualTo(1);
        assertThat(rows.get(2).getLineNo()).isEqualTo(3);
    }

    @Test
    void selectFailed_returnsOnlyResultGte3() {
        GroupLinkLabel label = insertLabel("失败导出测试分组");
        GroupLinkImportBatch batch = insertBatch(label.getId(), "failed_test.txt");

        // result=1 SUCCESS, result=2 ADOPTED, result=3 DUPLICATE, result=4 FORMAT_ERROR
        List<GroupLinkImportDetail> details = List.of(
                buildDetail(batch.getId(), 1, "chat.whatsapp.com/Ok1", 1, null),
                buildDetail(batch.getId(), 2, "chat.whatsapp.com/Ok2", 2, null),
                buildDetail(batch.getId(), 3, "chat.whatsapp.com/Dup1", 3, "批内重复"),
                buildDetail(batch.getId(), 4, "bad-url", 4, "格式错误")
        );
        detailMapper.batchInsert(details);

        List<GroupLinkImportDetailVoRow> failed = detailMapper.selectFailed(null, batch.getId());
        assertThat(failed).hasSize(2);
        // 全部 result >= 3
        failed.forEach(r -> assertThat(r.getResult()).isGreaterThanOrEqualTo(3));
    }

    @Test
    void selectPage_byLabelId_acrossBatches() {
        GroupLinkLabel label = insertLabel("跨批次查询测试分组");
        GroupLinkImportBatch batch1 = insertBatch(label.getId(), "batch1.txt");
        GroupLinkImportBatch batch2 = insertBatch(label.getId(), "batch2.txt");

        detailMapper.batchInsert(List.of(
                buildDetail(batch1.getId(), 1, "chat.whatsapp.com/B1L1", 1, null)
        ));
        detailMapper.batchInsert(List.of(
                buildDetail(batch2.getId(), 1, "chat.whatsapp.com/B2L1", 1, null)
        ));

        GroupLinkImportDetailQuery query = new GroupLinkImportDetailQuery();
        query.setLabelId(label.getId());
        query.setPage(1);
        query.setPageSize(10);

        assertThat(detailMapper.countByQuery(query)).isEqualTo(2);
        assertThat(detailMapper.selectPage(query)).hasSize(2);
    }
}
