package com.armada.platform.country.mapper;

import com.armada.platform.country.model.entity.Country;
import com.armada.platform.country.model.entity.CountryPhonePrefixMapping;
import java.util.Collection;
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
     * 查询全部启用的真实国家/地区，不受 IP 管理可见范围限制。
     *
     * @return 按 sort_order、id 排序的国家列表
     */
    List<Country> selectEnabled();

    /**
     * 查询共享国际区号到唯一展示国家的配置。
     *
     * @return 规范化区号唯一映射
     */
    List<CountryPhonePrefixMapping> selectPhonePrefixMappings();

    /**
     * 查询全部启用国家/地区,不受 IP 管理支持标记限制。
     *
     * <p>保留既有方法以兼容国家管理等现有调用；营销导出使用语义更明确的
     * {@link #selectEnabled()}。</p>
     *
     * @return 按 sort_order,id 排序的启用国家列表
     */
    List<Country> selectActive();

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

    /** 批量按 ISO2 查询国家展示信息；空集合由调用方拦截。 */
    List<Country> selectByIso2s(@Param("iso2s") Collection<String> iso2s);

    /** 按主键查询启用国家；保留用于既有 ID 引用调用。 */
    Country selectActiveById(@Param("id") Long id);

    /** 批量按主键查询国家展示信息；保留用于既有 ID 引用调用。 */
    List<Country> selectByIds(@Param("ids") Collection<Long> ids);

    /**
     * 更新国家级最近 IP 抽检时间。
     *
     * @param nameZh 中文展示名
     * @param checkedAt 抽检完成时间(epoch毫秒)
     * @return 更新行数
     */
    int updateLastIpSampleCheckAtByNameZh(@Param("nameZh") String nameZh, @Param("checkedAt") long checkedAt);
}
