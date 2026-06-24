package com.armada.group.model.vo;

import java.util.List;

/**
 * 群链接导入结果 VO。
 *
 * @param batchId    生成的批次 ID
 * @param total      解析总行数(空行不计)
 * @param inserted   新增行数(SUCCESS)
 * @param adopted    收编行数(ADOPTED:已存在链接归入本分组)
 * @param duplicated 批内重复跳过行数(DUPLICATE)
 * @param failed     格式不合格行数(FORMAT_ERROR)
 * @param errors     格式错误行的描述列表(如"第 3 行:格式错误:...")
 */
public record GroupLinkImportResultVO(Long batchId, int total, int inserted, int adopted,
                                      int duplicated, int failed, List<String> errors) {}
