package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.dto.HyperlinkRecipientQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientRow;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskSummaryRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** H4 详情摘要与唯一 recipient 流水的小字段查询。 */
@Mapper
public interface HyperlinkTaskDetailMapper {

    HyperlinkTaskSummaryRow selectSummary(@Param("taskId") long taskId);

    long countRecipients(HyperlinkRecipientQuery query);

    List<HyperlinkRecipientRow> selectRecipients(HyperlinkRecipientQuery query);

    List<HyperlinkRecipientRow> selectRecipientExportBatch(
            @Param("query") HyperlinkRecipientQuery query,
            @Param("snapshotAt") long snapshotAt,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit);
}
