package com.armada.hyperlink.data.service;

import com.armada.hyperlink.data.model.vo.DataPackageClaimPhone;
import com.armada.hyperlink.data.model.vo.DataPackageClaimSnapshot;
import java.util.List;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;

/** 任务域访问数据包冻结代次和领取能力的唯一跨域 Service。 */
public interface DataPackageRecipientClaimService {
    DataPackageClaimSnapshot snapshot(long dataPackageId);
    boolean isCurrentGeneration(long dataPackageId, int generation);
    List<DataPackageClaimPhone> claimBatch(long taskId, long dataPackageId, int generation,
            long cursor, long upperId, int limit, long now);
    int releasePhones(long taskId, long dataPackageId, int generation,
            List<String> phones, long now);
    int releaseOwnedBatch(long taskId, long dataPackageId, int generation, int limit, long now);

    /** 把任务 recipient 的明确协议结果幂等推进回号码池事实。 */
    void advanceDeliveryFact(long taskId, long dataPackageId, int generation, String phone,
            DataPackagePoolStatus targetStatus, long now);
}
