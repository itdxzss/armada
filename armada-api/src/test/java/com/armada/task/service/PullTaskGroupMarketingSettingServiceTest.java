package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskGroupMarketingSettingMapper;
import com.armada.task.model.dto.PullTaskGroupMarketingSettingDTO;
import com.armada.task.model.entity.PullTaskGroupMarketingSetting;
import com.armada.task.model.vo.PullTaskGroupMarketingSettingVO;
import com.armada.task.service.impl.PullTaskGroupMarketingSettingServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 拉群营销租户全局设置服务测试。 */
class PullTaskGroupMarketingSettingServiceTest {

    private final PullTaskGroupMarketingSettingMapper mapper =
            mock(PullTaskGroupMarketingSettingMapper.class);
    private final PullTaskGroupMarketingSettingService service =
            new PullTaskGroupMarketingSettingServiceImpl(mapper);

    @Test
    void returnsExplicitUnconfiguredStateWithoutCreatingDefaults() {
        assertThat(service.get()).isEqualTo(
                new PullTaskGroupMarketingSettingVO(false, null, null, null));

        verify(mapper).selectCurrent();
        verify(mapper, never()).upsert(any());
    }

    @Test
    void rejectsMissingNegativeAndZeroLimitValues() {
        assertValidationFailure(null);
        assertValidationFailure(new PullTaskGroupMarketingSettingDTO(null, 10, 1));
        assertValidationFailure(new PullTaskGroupMarketingSettingDTO(-1, 10, 1));
        assertValidationFailure(new PullTaskGroupMarketingSettingDTO(0, -1, 1));
        assertValidationFailure(new PullTaskGroupMarketingSettingDTO(0, 0, 0));

        verify(mapper, never()).upsert(any());
    }

    @Test
    void savesValidValuesWithOperatorAuditAndReturnsConfiguredState() {
        PullTaskGroupMarketingSettingDTO request =
                new PullTaskGroupMarketingSettingDTO(30, 60, 2);

        assertThat(service.save(request, 99L)).isEqualTo(
                new PullTaskGroupMarketingSettingVO(true, 30, 60, 2));

        ArgumentCaptor<PullTaskGroupMarketingSetting> captor =
                ArgumentCaptor.forClass(PullTaskGroupMarketingSetting.class);
        verify(mapper).upsert(captor.capture());
        PullTaskGroupMarketingSetting saved = captor.getValue();
        assertThat(saved.getCreatedBy()).isEqualTo(99L);
        assertThat(saved.getUpdatedBy()).isEqualTo(99L);
        assertThat(saved.getCreatedAt()).isPositive();
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(saved.getMarketingSilenceMinutes()).isEqualTo(30);
        assertThat(saved.getGroupLockdownMinutes()).isEqualTo(60);
        assertThat(saved.getMaxMarketingAccountsPerGroup()).isEqualTo(2);
    }

    private void assertValidationFailure(PullTaskGroupMarketingSettingDTO request) {
        assertThatThrownBy(() -> service.save(request, 99L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(exception.getMessage()).isEqualTo(
                            "静默和封控时间不能为负数，单群营销账号上限必须大于0");
                });
    }
}
