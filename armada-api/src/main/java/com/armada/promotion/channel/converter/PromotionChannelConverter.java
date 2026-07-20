package com.armada.promotion.channel.converter;

import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.promotion.channel.model.enums.PromotionPlatform;
import com.armada.promotion.channel.model.vo.PromotionChannelVO;
import com.armada.promotion.channel.model.vo.PromotionChannelVoRow;
import org.mapstruct.Mapper;
import org.springframework.util.StringUtils;

/** 渠道 Mapper 投影到接口出参的转换器。 */
@Mapper(componentModel = "spring")
public interface PromotionChannelConverter {

    /**
     * 把渠道分页投影和国家主数据组合为页面 VO。
     *
     * <p>推广链接和裂变链接在读取时动态生成，避免保存冗余完整 URL；转换过程不接触 Token。</p>
     *
     * @param row 渠道、域名、模板和探测状态投影
     * @param targetCountry 目标国家展示信息；混合渠道时为 null
     * @param preselectedCountry 预选区号国家展示信息
     * @return 渠道页面数据
     */
    default PromotionChannelVO toVO(
            PromotionChannelVoRow row,
            CountryReferenceVO targetCountry,
            CountryReferenceVO preselectedCountry) {
        if (row == null) {
            return null;
        }
        String baseLink = "https://" + row.getDomainHost() + "/" + row.getChannelCode();
        return new PromotionChannelVO(
                row.getId(),
                row.getChannelName(),
                row.getChannelCode(),
                row.getOwnerUserId(),
                row.getOwnerUserId(),
                row.getTargetCountryId(),
                targetCountry == null ? null : targetCountry.iso2(),
                targetCountry == null ? null : targetCountry.nameZh(),
                targetCountry == null ? null : targetCountry.flag(),
                row.getTargetCountryId() == null,
                row.getLandingTemplateId(),
                row.getTemplateName(),
                row.getPlatform(),
                PromotionPlatform.labelOf(row.getPlatform()),
                trackingStatus(row),
                baseLink,
                baseLink + "/1",
                row.getPreselectedCountryId(),
                preselectedCountry == null ? null : preselectedCountry.iso2(),
                preselectedCountry == null ? null : preselectedCountry.nameZh(),
                preselectedCountry == null ? null : preselectedCountry.phonePrefix(),
                preselectedCountry == null ? null : preselectedCountry.flag(),
                row.getStatus(),
                asBoolean(row.getIsInAppOpenAllowed()),
                asBoolean(row.getIsMarketingAllowed()),
                row.getCreatedAt());
    }

    /** 根据平台能力、配置完整性和最近探测结果生成稳定的页面状态码。 */
    private static String trackingStatus(PromotionChannelVoRow row) {
        PromotionPlatform platform = null;
        if (row.getPlatform() != null) {
            for (PromotionPlatform value : PromotionPlatform.values()) {
                if (value.code() == row.getPlatform()) {
                    platform = value;
                    break;
                }
            }
        }
        if (platform == null || !platform.capiSupported()) {
            return "NOT_APPLICABLE";
        }
        if (!StringUtils.hasText(row.getTrackingId())) {
            return "UNCONFIGURED";
        }
        if (row.getLastProbeStatus() == null) {
            return "UNPROBED";
        }
        return switch (row.getLastProbeStatus()) {
            case 0 -> "PROBING";
            case 1 -> "NORMAL";
            case 2 -> "ABNORMAL";
            default -> "UNKNOWN";
        };
    }

    /** 将数据库 TINYINT(1) 转为接口布尔值。 */
    private static Boolean asBoolean(Integer value) {
        return value != null && value == 1;
    }
}
