package com.armada.promotion.channel.support;

/** 统一构造渠道对外访问链接，确保列表展示与 CAPI 事件来源使用相同协议。 */
public final class PromotionChannelLinkBuilder {

    private static final String HTTP_PREFIX = "http://";

    private PromotionChannelLinkBuilder() {
    }

    /**
     * 使用已规范化的域名和推广码构造渠道推广链接。
     *
     * @param domainHost 不含协议、端口和路径的域名
     * @param channelCode 渠道推广码
     * @return HTTP 渠道推广链接
     */
    public static String build(String domainHost, String channelCode) {
        return HTTP_PREFIX + domainHost + "/" + channelCode;
    }
}
