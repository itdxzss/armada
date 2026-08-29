package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.data.model.vo.DataPackageClaimPhone;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipientClaim;
import com.armada.hyperlink.task.service.HyperlinkRecipientClaimService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** claim 完成后重复恢复不再生成第二批 recipient。 */
class HyperlinkRecipientClaimServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void completedClaimIsIdempotentOnRecovery() {
        HyperlinkTaskRecipientClaimMapper claimMapper = mock(HyperlinkTaskRecipientClaimMapper.class);
        HyperlinkTaskRecipientMapper recipientMapper = mock(HyperlinkTaskRecipientMapper.class);
        DataPackageRecipientClaimService dataPackageService = mock(DataPackageRecipientClaimService.class);
        HyperlinkTaskRecipientClaim claim = claim();
        TenantContext.set(7L);
        when(claimMapper.selectByTaskId(7L, 10L)).thenReturn(claim);
        when(dataPackageService.claimBatch(anyLong(), anyLong(), anyInt(), anyLong(),
                anyLong(), anyInt(), anyLong()))
                .thenReturn(List.of(phone(1L, "8613800000001"), phone(2L, "8613800000002")));
        when(claimMapper.advance(anyLong(), anyInt(), anyLong(), anyInt(), anyBoolean(), anyLong()))
                .thenAnswer(invocation -> {
                    claim.setClaimStatus(3);
                    claim.setClaimedPhoneCount(2);
                    return 1;
                });
        HyperlinkRecipientClaimService service = new HyperlinkRecipientClaimService(
                claimMapper, recipientMapper, dataPackageService);

        assertThat(service.claimNext(10L).claimedThisBatch()).isEqualTo(2);
        assertThat(service.claimNext(10L).claimedThisBatch()).isZero();

        verify(recipientMapper, times(1)).insertIgnoreBatch(anyList());
        verify(dataPackageService, times(1)).claimBatch(anyLong(), anyLong(), anyInt(),
                anyLong(), anyLong(), anyInt(), anyLong());
    }

    private HyperlinkTaskRecipientClaim claim() {
        HyperlinkTaskRecipientClaim claim = new HyperlinkTaskRecipientClaim();
        claim.setId(5L);
        claim.setHyperlinkTaskId(10L);
        claim.setDataPackageId(20L);
        claim.setDataPackageGeneration(1);
        claim.setClaimUpperPhoneId(2L);
        claim.setScanCursorPhoneId(0L);
        claim.setClaimedPhoneCount(0);
        claim.setClaimStatus(1);
        claim.setVersion(1);
        return claim;
    }

    private DataPackageClaimPhone phone(long id, String value) {
        return new DataPackageClaimPhone(id, 3L, value, "CN");
    }
}
