package com.armada.platform.country.mapper;

import com.armada.platform.country.model.entity.Country;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 国家/地区主数据 Mapper。country 表无 tenant_id,必须在 MyBatisConfig.IGNORED_TABLES 中。
 */
@Mapper
public interface CountryMapper {

    /**
     * 统计未软删国家/地区行数。
     *
     * @return 未软删行数
     */
    long countActive();

    /**
     * 查询启用且可用于 IP 管理下拉的国家/地区。
     *
     * @return 按 sort_order,id 排序的国家列表
     */
    List<Country> selectIpSupported();

    /**
     * 按二字母国家/地区码查询启用国家。
     *
     * @param iso2 ISO/CLDR 二字母国家/地区码,大写
     * @return 启用国家;不存在时返回 null
     */
    Country selectActiveByIso2(@Param("iso2") String iso2);

    /**
     * 按中文展示名查询启用国家。
     *
     * @param nameZh 中文展示名
     * @return 启用国家;不存在时返回 null
     */
    Country selectActiveByNameZh(@Param("nameZh") String nameZh);

    /**
     * 更新国家级最近 IP 抽检时间。
     *
     * @param nameZh 中文展示名
     * @param checkedAt 抽检完成时间(epoch毫秒)
     * @return 更新行数
     */
    int updateLastIpSampleCheckAtByNameZh(@Param("nameZh") String nameZh, @Param("checkedAt") long checkedAt);
}
