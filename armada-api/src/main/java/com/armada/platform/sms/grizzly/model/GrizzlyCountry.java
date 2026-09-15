package com.armada.platform.sms.grizzly.model;

import java.util.Optional;

/**
 * 平台国家目录项；ID 与 E.164 国家电话区号无关。
 * @param id 供应商国家 ID
 * @param englishName 英文名称
 * @param chineseName 中文名称，供应商未提供时为空
 * @param russianName 俄文名称，供应商未提供时为空
 */
public record GrizzlyCountry(String id, String englishName, Optional<String> chineseName,
                             Optional<String> russianName) {
}
