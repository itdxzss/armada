package com.armada.marketing.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.util.StringUtils;

/**
 * 营销任务目标维度。
 *
 * <p>固定群组维度会在创建任务时落账号+群组目标;账号动态维度只落账号目标,
 * 每轮发送前再读取该账号导入云控后新增的当前群。</p>
 */
public enum MarketingTargetScope {
    GROUP_FIXED(1, "GROUP_FIXED"),
    ACCOUNT_DYNAMIC(2, "ACCOUNT_DYNAMIC");

    private final int code;
    private final String apiValue;

    MarketingTargetScope(int code, String apiValue) {
        this.code = code;
        this.apiValue = apiValue;
    }

    public int code() {
        return code;
    }

    public String apiValue() {
        return apiValue;
    }

    /**
     * 前端未传 targetScope 时兼容旧协议:只要提交了 groupLinkIds,就按固定群组维度处理。
     */
    public static MarketingTargetScope fromApiValue(String value) {
        if (!StringUtils.hasText(value)) {
            return GROUP_FIXED;
        }
        for (MarketingTargetScope scope : values()) {
            if (scope.apiValue.equalsIgnoreCase(value.trim())) {
                return scope;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "未知的营销目标维度: " + value);
    }

    public static String apiValueOf(Integer code) {
        if (code == null) {
            return GROUP_FIXED.apiValue;
        }
        for (MarketingTargetScope scope : values()) {
            if (scope.code == code) {
                return scope.apiValue;
            }
        }
        return GROUP_FIXED.apiValue;
    }

    public boolean isGroupFixed() {
        return this == GROUP_FIXED;
    }

    public boolean isAccountDynamic() {
        return this == ACCOUNT_DYNAMIC;
    }
}
