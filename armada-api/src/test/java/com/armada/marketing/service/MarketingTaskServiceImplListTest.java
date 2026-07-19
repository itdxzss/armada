package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountService;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.dto.MarketingTaskQuery;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.model.vo.MarketingTaskVO;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.marketing.service.impl.MarketingAccountTreeRealtimeService;
import com.armada.marketing.service.impl.MarketingTaskServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 营销任务列表模板信息补充单测。
 *
 * <p>真实 Mapper SQL、软删除和租户隔离由 {@link MarketingTaskCreateReadDbTest} 覆盖；
 * 本类只锁定当前页模板 ID 去重后必须一次批量读取，禁止列表出现逐任务查询。</p>
 */
@ExtendWith(MockitoExtension.class)
class MarketingTaskServiceImplListTest {

    @Mock
    private MarketingTaskMapper taskMapper;

    @Mock
    private MarketingTemplateMapper templateMapper;

    @Mock
    private MarketingTemplateService templateService;

    @Mock
    private MarketingAccountTreeRealtimeService accountTreeRealtimeService;

    @Mock
    private MarketingAccountOccupancyService occupancyService;

    @Mock
    private AccountService accountService;

    @InjectMocks
    private MarketingTaskServiceImpl service;

    @Test
    void listTasks_batchesDistinctTemplateIdsAndKeepsRowsWithMissingTemplates() {
        MarketingTaskQuery query = new MarketingTaskQuery();
        query.setPage(1);
        query.setPageSize(10);
        MarketingTask first = task(1L, 10L, "共享模板任务A");
        MarketingTask second = task(2L, 10L, "共享模板任务B");
        MarketingTask missing = task(3L, 99L, "模板已删除任务");
        MarketingTemplate shared = template(
                10L, "活动标题", "活动正文", "https://example.com/promo");
        when(taskMapper.countPage(query)).thenReturn(3L);
        when(taskMapper.selectPage(query)).thenReturn(List.of(first, second, missing));
        when(templateMapper.selectByIds(List.of(10L, 99L))).thenReturn(List.of(shared));

        List<MarketingTaskVO> rows = service.listTasks(query).list();

        assertThat(rows).hasSize(3);
        assertThat(rows.subList(0, 2)).allSatisfy(row -> {
            assertThat(row.marketingTemplateContent()).isEqualTo("活动标题");
            assertThat(row.marketingTemplateBodyText()).isEqualTo("活动正文");
            assertThat(row.marketingTemplatePromotionLink()).isEqualTo("https://example.com/promo");
        });
        assertThat(rows.get(2).marketingTemplateContent()).isNull();
        assertThat(rows.get(2).marketingTemplateBodyText()).isNull();
        assertThat(rows.get(2).marketingTemplatePromotionLink()).isNull();
        verify(templateMapper).selectByIds(List.of(10L, 99L));
        verify(templateMapper, never()).selectById(anyLong());
    }

    private static MarketingTask task(long id, long templateId, String name) {
        MarketingTask task = new MarketingTask();
        task.setId(id);
        task.setTaskName(name);
        task.setMarketingTemplateId(templateId);
        task.setMarketingTemplateName("模板" + templateId);
        task.setStatus(MarketingTaskStatus.PENDING.code());
        return task;
    }

    private static MarketingTemplate template(long id, String content, String bodyText, String promotionLink) {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(id);
        template.setContent(content);
        template.setBodyText(bodyText);
        template.setPromotionLink(promotionLink);
        return template;
    }
}
