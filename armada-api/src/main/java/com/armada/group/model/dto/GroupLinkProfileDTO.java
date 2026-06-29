package com.armada.group.model.dto;

/**
 * 群组列表本地资料更新请求。
 *
 * @param groupName Armada 运营侧自定义群名称;传空字符串表示清空
 * @param remark    运营备注;传空字符串表示清空
 * @param avatarUrl 运营侧展示头像 URL;传空字符串表示清空
 */
public record GroupLinkProfileDTO(String groupName, String remark, String avatarUrl) {
}
