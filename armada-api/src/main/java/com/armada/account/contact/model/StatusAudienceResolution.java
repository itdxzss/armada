package com.armada.account.contact.model;
import java.util.List;
/** 发送前解析结果。PENDING 时不应递增发送重试次数。 */
public record StatusAudienceResolution(StatusAudienceView view, List<String> jids) {
    public StatusAudienceResolution { jids = List.copyOf(jids); }
}
