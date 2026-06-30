package com.armada.platform.country.service.impl;

import com.armada.platform.country.mapper.CountryMapper;
import com.armada.platform.country.model.entity.Country;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 国家/地区主数据服务实现。
 *
 * <p>国家表是平台级主数据,不带 tenant_id。当前只承担 IP 管理下拉和旧 IP 代理池 region
 * 兼容转换两个职责:下拉对前端暴露稳定的 ISO2 值,写入/查询 IP 代理池时仍转换为历史使用的中文 region 快照。</p>
 */
@Service
public class CountryServiceImpl implements CountryService {

    /** 当前只开放给 IP 管理使用的选项范围。后续若有账号/群链接等差异化范围,在这里扩展 scope。 */
    private static final String IP_SCOPE = "ip";

    /** 前端选择“混合（不限国家）”时提交的稳定值。该值不是国家,不入 country 表。 */
    private static final String MIXED_VALUE = "MIXED";

    /** 兼容 ip_proxy.region 既有中文存储和分配优先级。 */
    private static final String MIXED_REGION = "混合（不限国家）";

    /** 下拉第一项虚拟选项,用于表达不限真实国家的混合代理池。 */
    private static final CountryOptionVO MIXED_OPTION =
            new CountryOptionVO(MIXED_VALUE, null, MIXED_REGION, "", "🌐", true);

    private final CountryMapper mapper;

    public CountryServiceImpl(CountryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查询国家下拉选项。
     *
     * <p>{@code scope} 为空时按 IP 管理处理;当前不支持其它范围,避免前端误用同一个接口表达不同业务语义。
     * 返回值固定把 {@code MIXED} 虚拟项放在第一位,真实国家只取启用且允许 IP 管理展示的主数据。</p>
     */
    @Override
    public CountryOptionsVO options(String scope) {
        String normalizedScope = StringUtils.hasText(scope) ? scope.trim() : IP_SCOPE;
        if (!IP_SCOPE.equalsIgnoreCase(normalizedScope)) {
            throw new BusinessException(ErrorCode.VALIDATION, "不支持的国家选项范围: " + scope);
        }
        List<CountryOptionVO> rows = new ArrayList<>();
        rows.add(MIXED_OPTION);
        for (Country country : mapper.selectIpSupported()) {
            rows.add(new CountryOptionVO(
                    country.getIso2(),
                    country.getIso2(),
                    country.getNameZh(),
                    country.getPhonePrefix() == null ? "" : country.getPhonePrefix(),
                    country.getFlag() == null ? "" : country.getFlag(),
                    false));
        }
        return new CountryOptionsVO(List.copyOf(rows));
    }

    /**
     * 把前端提交的国家值解析成 IP 代理池当前使用的中文 region。
     *
     * <p>新下拉提交真实国家 ISO2,旧页面/旧调用可能仍传中文名,所以解析顺序是:
     * MIXED 虚拟值 -> 二字母 ISO2 -> 中文展示名。找不到启用国家时抛业务校验异常,
     * 避免把未知值写入 {@code ip_proxy.region} 后影响后续分配优先级。</p>
     */
    @Override
    public String resolveIpRegion(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (MIXED_VALUE.equalsIgnoreCase(trimmed) || MIXED_REGION.equals(trimmed)) {
            return MIXED_REGION;
        }
        Country country = null;
        if (trimmed.length() == 2) {
            country = mapper.selectActiveByIso2(trimmed.toUpperCase(Locale.ROOT));
        }
        if (country == null) {
            country = mapper.selectActiveByNameZh(trimmed);
        }
        if (country == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家不存在或已停用: " + trimmed);
        }
        return country.getNameZh();
    }
}
