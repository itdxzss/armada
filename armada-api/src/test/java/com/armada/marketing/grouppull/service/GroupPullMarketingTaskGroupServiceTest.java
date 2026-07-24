package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.dto.GroupPullMarketingGroupQuery;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingGroupVO;
import com.armada.marketing.grouppull.service.impl.GroupPullMarketingTaskServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 拉群营销群组明细分页服务测试。 */
class GroupPullMarketingTaskGroupServiceTest {

    @Test
    void groupsUsesNormalizedPagingAndReturnsMapperRows() {
        AtomicReference<GroupPullMarketingGroupQuery> capturedQuery = new AtomicReference<>();
        GroupPullMarketingGroupVO group = group();
        GroupPullMarketingMapper mapper = mapper((method, args) -> switch (method) {
            case "selectTaskById" -> task();
            case "countTaskGroups" -> 12L;
            case "selectTaskGroups" -> {
                capturedQuery.set((GroupPullMarketingGroupQuery) args[1]);
                yield List.of(group);
            }
            default -> throw new UnsupportedOperationException(method);
        });
        GroupPullMarketingGroupQuery query = new GroupPullMarketingGroupQuery();
        query.setPage(2);
        query.setPageSize(10);

        PageResult<GroupPullMarketingGroupVO> result = service(mapper).groups(101L, query);

        assertThat(result.list()).containsExactly(group);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.total()).isEqualTo(12);
        assertThat(capturedQuery.get()).isSameAs(query);
    }

    @Test
    void groupsRejectsUnknownTaskInsteadOfReturningAnEmptyPage() {
        GroupPullMarketingMapper mapper = mapper((method, args) -> {
            if ("selectTaskById".equals(method)) {
                return null;
            }
            throw new UnsupportedOperationException(method);
        });

        assertThatThrownBy(() -> service(mapper).groups(999L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("拉群营销任务配置不存在");
    }

    private static GroupPullMarketingTaskServiceImpl service(GroupPullMarketingMapper mapper) {
        return new GroupPullMarketingTaskServiceImpl(
                mapper, null, null, null, null, null, null, null);
    }

    private static GroupPullMarketingTask task() {
        GroupPullMarketingTask task = new GroupPullMarketingTask();
        task.setMarketingTaskId(101L);
        return task;
    }

    private static GroupPullMarketingGroupVO group() {
        return new GroupPullMarketingGroupVO(
                501L, "10001", "10002", "营销群-1", "group@g.us", null,
                1, 3, null, 0, 1, true, 1, 1, 4, 5,
                "添加料子失败", null, null, 1_000L);
    }

    private static GroupPullMarketingMapper mapper(Invocation invocation) {
        Object proxy = Proxy.newProxyInstance(
                GroupPullMarketingMapper.class.getClassLoader(),
                new Class<?>[]{GroupPullMarketingMapper.class},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return "GroupPullMarketingMapperTestProxy";
                    }
                    return invocation.invoke(method.getName(), args);
                });
        return GroupPullMarketingMapper.class.cast(proxy);
    }

    @FunctionalInterface
    private interface Invocation {

        Object invoke(String method, Object[] args);
    }
}
