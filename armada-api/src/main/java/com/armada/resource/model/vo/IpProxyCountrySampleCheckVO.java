package com.armada.resource.model.vo;

import java.util.List;

/**
 * 国家级 IP 抽样检测结果。
 *
 * @param region 国家/地区中文快照
 * @param sampleCount 本次实际检测数量
 * @param lastSampleCheckAt 国家级抽检完成时间(epoch毫秒)
 * @param results IP 级检测结果
 */
public record IpProxyCountrySampleCheckVO(
        String region,
        Integer sampleCount,
        Long lastSampleCheckAt,
        List<IpProxyCheckResultVO> results) {
}
