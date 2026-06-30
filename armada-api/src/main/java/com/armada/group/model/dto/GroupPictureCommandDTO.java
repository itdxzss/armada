package com.armada.group.model.dto;

/**
 * 修改 WhatsApp 真实群头像请求。
 *
 * @param accountId 操作账号 ID
 * @param url       头像 URL;与 base64 二选一
 * @param base64    头像 base64;与 url 二选一
 */
public record GroupPictureCommandDTO(Long accountId, String url, String base64) {
}
