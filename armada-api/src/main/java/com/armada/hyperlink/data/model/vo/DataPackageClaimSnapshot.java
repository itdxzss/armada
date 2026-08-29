package com.armada.hyperlink.data.model.vo;

import java.util.List;

/** 报价冻结所需的数据包当前代领取快照。 */
public record DataPackageClaimSnapshot(long dataPackageId, int generation, String packageName,
        long upperPhoneId, int recipientCount, List<DataPackageClaimCountryCount> countryCounts) { }
