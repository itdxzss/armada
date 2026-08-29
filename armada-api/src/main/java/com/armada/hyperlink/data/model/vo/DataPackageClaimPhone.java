package com.armada.hyperlink.data.model.vo;

/** recipient 领取批次返回给任务域的最小号码快照。 */
public record DataPackageClaimPhone(long id, long sourceImportId, String phone, String countryIso2) { }
