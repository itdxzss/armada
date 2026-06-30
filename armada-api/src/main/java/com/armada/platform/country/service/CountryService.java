package com.armada.platform.country.service;

import com.armada.platform.country.model.vo.CountryOptionsVO;

/**
 * 国家/地区主数据服务。跨域消费者只能依赖本 Service,不能直接碰 admin mapper/entity。
 */
public interface CountryService {

    /**
     * 查询国家下拉选项。
     *
     * @param scope 选项范围,当前支持 ip;为空时按 ip 处理
     * @return 国家选项列表
     */
    CountryOptionsVO options(String scope);

    /**
     * 把前端提交的国家值解析为现有 IP 代理池 region 中文快照。
     *
     * @param value 真实国家为二字母码或中文名;混合为 MIXED/mixed/混合（不限国家）
     * @return 可写入/查询 ip_proxy.region 的中文 region;空值返回 null
     */
    String resolveIpRegion(String value);
}
