package com.armada.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 邀请码大小写敏感的真库补充测试。
 *
 * <p>只有真 MySQL 能证明 {@code normalized_link} 的 {@code ascii_bin} 排序规则生效：
 * 表默认 {@code utf8mb4_0900_ai_ci} 大小写不敏感，漏声明会把仅大小写不同的两条
 * 邀请码判为重复；而 H2 默认大小写敏感，这个缺陷在内存测试里静默通过。</p>
 *
 * <p>本类是可选补充验证，不是本地完成门禁。执行前必须确认目标环境，
 * 用 {@code armada-api/dbtest.sh PullTaskNormalLinkCollationDbTest} 运行。</p>
 */
class PullTaskNormalLinkCollationDbTest extends DbTestBase {

    @Autowired
    private PullTaskGroupExecutionMapper mapper;

    @Test
    void inviteCodesDifferingOnlyByCaseAreDistinctLinks() {
        long taskId = System.nanoTime();

        mapper.insertDraft(draft(taskId, 1, "chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv", 1));
        mapper.insertDraft(draft(taskId, 2, "chat.whatsapp.com/abcdefghijklmnopqrstuv", 2));

        List<PullTaskGroupExecution> rows = mapper.selectByTaskId(taskId);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(PullTaskGroupExecution::getNormalizedLink)
                .containsExactly(
                        "chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv",
                        "chat.whatsapp.com/abcdefghijklmnopqrstuv");
    }

    private PullTaskGroupExecution draft(long taskId, int seq, String link, int fileIndex) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(taskId);
        row.setSeq(seq);
        row.setNormalizedLink(link);
        row.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        row.setSourceLinkLineNo(seq);
        row.setSourceFileIndex(fileIndex);
        row.setSourceFileName("material-" + fileIndex + ".txt");
        row.setTotalLineCount(10);
        row.setValidMemberCount(8);
        row.setInvalidLineCount(1);
        row.setDuplicateLineCount(1);
        row.setExecutionStatus(PullTaskExecutionStatus.DRAFT.code());
        long now = System.currentTimeMillis();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }
}
