package com.armada.group.model.vo;

import java.util.List;

/**
 * 群链接导入结果 VO。
 *
 * @param batchId    生成的批次 ID
 * @param total      解析总行数(空行不计)
 * @param inserted   成功行数(SUCCESS:新增插入,或复活之前软删的同 url 链接)
 * @param exists     已存在行数(EXISTS:同 url 已活跃存在,未导入、原链接不动)
 * @param duplicated 批内重复跳过行数(DUPLICATE)
 * @param failed     格式不合格行数(FORMAT_ERROR)
 * @param errors     格式错误行的描述列表(如"第 3 行:格式错误:...")
 */
public record GroupLinkImportResultVO(Long batchId, int total, int inserted, int exists,
                                      int duplicated, int failed, List<String> errors) {}
