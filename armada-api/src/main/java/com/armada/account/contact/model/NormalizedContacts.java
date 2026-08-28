package com.armada.account.contact.model;

import java.util.List;

/**
 * 一次通讯录协议快照归一化后的结果。
 *
 * @param rows 去重、裁空后的联系人行
 * @param contactNum 联系人总数
 * @param namedNum 通讯录里有名字的数量（竞品「好友数」口径）
 * @param mutualNum 双向好友数量（协议暂不暴露该标记时恒为 0）
 */
public record NormalizedContacts(List<Row> rows, int contactNum, int namedNum, int mutualNum) {

    /** 空结果常量。 */
    public static final NormalizedContacts EMPTY = new NormalizedContacts(List.of(), 0, 0, 0);

    /**
     * 归一化后的单个联系人。
     *
     * @param phone 不带加号的纯数字号码
     * @param jid 规范用户 JID
     * @param fullName 通讯录全名，空白值归一为 null
     * @param firstName 通讯录名，空白值归一为 null
     * @param pushName 对方设置的展示名，空白值归一为 null
     * @param businessName 商业号认证名，空白值归一为 null
     * @param named 通讯录里是否有名字
     * @param mutual 是否双向好友
     */
    public record Row(
            String phone,
            String jid,
            String fullName,
            String firstName,
            String pushName,
            String businessName,
            boolean named,
            boolean mutual
    ) {
    }
}
