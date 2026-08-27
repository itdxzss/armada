package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.country.service.CountryService;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.task.mapper.PullTaskGroupMarketingCandidateMapper;
import com.armada.task.mapper.PullTaskGroupMarketingGroupOccupancyMapper;
import com.armada.task.model.dto.PullTaskGroupMarketingWaitingPoolAddDTO;
import com.armada.task.model.entity.PullTaskGroupMarketingGroupOccupancy;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateRow;
import com.armada.task.service.impl.PullTaskGroupMarketingGroupServiceImpl;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/** 拉群营销候选群组服务的并发冲突结果测试。 */
@ExtendWith(MockitoExtension.class)
class PullTaskGroupMarketingGroupServiceImplTest {

    private static final String TOKEN = "123e4567-e89b-12d3-a456-426614174000";
    private static final String GROUP_JID = "120363001@g.us";

    @Mock
    private PullTaskGroupMarketingCandidateMapper candidateMapper;

    @Mock
    private PullTaskGroupMarketingGroupOccupancyMapper occupancyMapper;

    @Mock
    private CountryService countryService;

    private PullTaskGroupMarketingGroupServiceImpl service;

    /** 装配只使用 test double 的服务。 */
    @BeforeEach
    void setUp() {
        DataScopeContext.open(DataScope.self(7L));
        service = new PullTaskGroupMarketingGroupServiceImpl(
                candidateMapper, occupancyMapper, countryService);
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    /** 数据库唯一键竞争失败时必须逐群拒绝，不能把冲突群误报为入池成功。 */
    @Test
    void addWaitingReportsConcurrentOccupancyConflict() {
        when(candidateMapper.selectByGroupJids(List.of(GROUP_JID), DataScope.self(7L)))
                .thenReturn(List.of(healthyCandidate()));
        when(occupancyMapper.selectCreatorByToken(TOKEN)).thenReturn(null);
        when(occupancyMapper.insertWaiting(any(PullTaskGroupMarketingGroupOccupancy.class)))
                .thenThrow(new DuplicateKeyException("duplicate occupancy"));
        when(occupancyMapper.selectActiveByGroupJids(List.of(GROUP_JID)))
                .thenReturn(List.of(otherPoolOccupancy()));
        when(occupancyMapper.selectWaitingByToken(TOKEN, 7L)).thenReturn(List.of());

        var result = service.addWaiting(
                new PullTaskGroupMarketingWaitingPoolAddDTO(
                        TOKEN, "印度营销", null, List.of(GROUP_JID)),
                7L);

        assertThat(result.groups()).isEmpty();
        assertThat(result.rejected())
                .singleElement()
                .satisfies(rejected -> {
                    assertThat(rejected.groupJid()).isEqualTo(GROUP_JID);
                    assertThat(rejected.reason()).contains("其他等待池或任务占用");
                });
        verify(occupancyMapper).releaseExpiredWaiting(anyLong());
    }

    private static PullTaskGroupMarketingCandidateRow healthyCandidate() {
        PullTaskGroupMarketingCandidateRow row = new PullTaskGroupMarketingCandidateRow();
        row.setGroupLinkId(1001L);
        row.setGroupJid(GROUP_JID);
        row.setHistorical(true);
        row.setAdminRelationCount(1);
        row.setEligibleAccountCount(1);
        row.setOnlineAccountCount(1);
        row.setHealthStatus(1);
        row.setBanned(false);
        return row;
    }

    private static PullTaskGroupMarketingGroupOccupancy otherPoolOccupancy() {
        PullTaskGroupMarketingGroupOccupancy row = new PullTaskGroupMarketingGroupOccupancy();
        row.setGroupJid(GROUP_JID);
        row.setReservationToken("other-pool");
        row.setCreatedBy(8L);
        return row;
    }
}
