package com.armada.platform.country.service;

import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.model.vo.CountryReferenceVO;
import java.util.Collection;
import java.util.Map;

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

    /**
     * 按 WhatsApp 账号手机号国际区号解析 IP 代理池 region 中文快照。
     *
     * <p>实现会只保留账号和区号中的数字,按国家主数据 phone_prefix 做最长前缀匹配。
     * 找不到匹配国家时返回 null,由调用方决定是否走混合池。</p>
     *
     * @param wsPhone WhatsApp 账号手机号或 JID
     * @return 可写入/查询 ip_proxy.region 的中文 region;未匹配时返回 null
     */
    String resolveIpRegionByPhonePrefix(String wsPhone);

    /**
     * 批量按 WhatsApp 账号手机号国际区号解析 IP 代理池 region 中文快照。
     *
     * <p>用于账号批量上线场景,避免每个账号都重复读取国家主数据。返回 Map 以入参手机号原值为 key,
     * 匹配不到时 value 为 null。</p>
     *
     * @param wsPhones WhatsApp 账号手机号或 JID 集合
     * @return 原手机号到中文 region 的映射;未匹配时 value 为 null
     */
    Map<String, String> resolveIpRegionsByPhonePrefix(Collection<String> wsPhones);

    /**
     * 批量按已确认的 WhatsApp 国际手机号解析启用国家展示信息。
     *
     * <p>只接受有效国际号码或明确的 PN JID。无效号码、LID、未知 JID 与未启用国家
     * 不会出现在返回映射中,也不会回退到区号前缀猜测。</p>
     *
     * @param wsPhones WhatsApp 国际手机号或 PN JID 集合
     * @return 原手机号到国家展示引用的映射
     */
    Map<String, CountryReferenceVO> resolveActiveCountriesByPhoneNumbers(
            Collection<String> wsPhones);

    /**
     * 按检测出的 ISO2 国家码解析为 IP 代理池 region 中文快照。
     *
     * @param iso2 检测出的二字母国家码
     * @return 可写入 ip_proxy.region 的中文 region
     */
    String resolveIpRegionByIso2(String iso2);

    /**
     * 校验并返回国家下拉 value 对应的启用选项。
     *
     * @param value 真实国家 ISO2 或 MIXED
     * @param mixedAllowed 是否允许 MIXED 虚拟选项
     * @return 规范化后的国家选项
     */
    CountryOptionVO requireActiveOption(String value, boolean mixedAllowed);

    /**
     * 批量查询国家选项，用于列表一次性补齐名称、区号和国旗，避免 N+1。
     *
     * @param values 国家 ISO2/MIXED 集合
     * @return 以规范化 value 为 key 的选项映射
     */
    Map<String, CountryOptionVO> optionsByValues(Collection<String> values);

    /**
     * 按主键校验并返回启用国家。保留该方法兼容仍使用 country.id 的既有业务域。
     *
     * @param countryId 国家主键
     * @return 国家只读引用
     */
    CountryReferenceVO requireActiveReference(Long countryId);

    /**
     * 批量按主键查询国家展示引用。保留该方法兼容仍使用 country.id 的既有业务域。
     *
     * @param countryIds 国家主键集合
     * @return 以 country.id 为 key 的引用映射
     */
    Map<Long, CountryReferenceVO> referencesByIds(Collection<Long> countryIds);
}
