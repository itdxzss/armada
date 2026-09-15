package com.armada.platform.sms.grizzly.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接码价格档位的查询快照，不代表已锁价、已预留号码或账号质量等级。
 * @param country 国家目录 ID
 * @param service 服务目录代码
 * @param cost 当前档位单价；价格接口未给出币种，不能默认美元
 * @param count getPricesV2 返回的当前档位库存，不是 getPricesV3 的供应商总库存
 * @param providerIds getPricesV3 中库存非零且包含该价格的供应商 ID；两次查询不原子，可能为空
 */
public record GrizzlyPriceTier(String country, String service, BigDecimal cost, long count,
                               List<String> providerIds) {

    /** 供应商筛选信息使用不可变快照，避免查询返回后被调用方改写。 */
    public GrizzlyPriceTier {
        providerIds = List.copyOf(providerIds);
    }
}
