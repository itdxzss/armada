package com.armada.promotion.channel.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Arrays;
import org.springframework.util.StringUtils;

/** Meta/Facebook Conversion API 当前允许配置的标准事件。 */
public enum FacebookStandardEvent {
    PAGE_VIEW("PageView", "浏览页面"),
    VIEW_CONTENT("ViewContent", "查看内容"),
    SEARCH("Search", "搜索"),
    ADD_TO_CART("AddToCart", "加入购物车"),
    ADD_TO_WISHLIST("AddToWishlist", "加入心愿单"),
    INITIATE_CHECKOUT("InitiateCheckout", "发起结账"),
    ADD_PAYMENT_INFO("AddPaymentInfo", "添加支付信息"),
    PURCHASE("Purchase", "购买"),
    LEAD("Lead", "潜在客户"),
    COMPLETE_REGISTRATION("CompleteRegistration", "完成注册"),
    CONTACT("Contact", "联系"),
    CUSTOMIZE_PRODUCT("CustomizeProduct", "定制产品"),
    DONATE("Donate", "捐赠"),
    FIND_LOCATION("FindLocation", "查找门店"),
    SCHEDULE("Schedule", "预约"),
    START_TRIAL("StartTrial", "开始试用"),
    SUBMIT_APPLICATION("SubmitApplication", "提交申请"),
    SUBSCRIBE("Subscribe", "订阅");

    private final String code;
    private final String nameZh;

    FacebookStandardEvent(String code, String nameZh) {
        this.code = code;
        this.nameZh = nameZh;
    }

    public String code() {
        return code;
    }

    public String nameZh() {
        return nameZh;
    }

    public String nameEn() {
        return code;
    }

    /**
     * 未填写时返回业务默认事件，填写时只接受精确的官方代码。
     *
     * @param value 请求中的事件代码
     * @param defaultEvent 当前业务阶段默认事件
     * @return 可持久化的标准代码
     */
    public static String requireOrDefault(String value, FacebookStandardEvent defaultEvent) {
        if (!StringUtils.hasText(value)) {
            return defaultEvent.code;
        }
        String normalized = value.trim();
        return Arrays.stream(values())
                .filter(event -> event.code.equals(normalized))
                .findFirst()
                .map(FacebookStandardEvent::code)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.VALIDATION,
                        "上报事件必须是 Meta 官方标准事件: " + normalized));
    }

    /** 判断持久化快照是否属于当前支持的官方标准事件。 */
    public static boolean supports(String value) {
        return StringUtils.hasText(value)
                && Arrays.stream(values()).anyMatch(event -> event.code.equals(value.trim()));
    }
}
