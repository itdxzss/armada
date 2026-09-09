package com.armada.platform.protocol.model.result;
import java.util.List;
/** 完整单页候选标识；不表达双向好友或动态可见性。 */
public record CloudContactsPage(List<String> jids, String version, String nextCursor, boolean hasNextPage) {
    public CloudContactsPage { jids = List.copyOf(jids); }
}
