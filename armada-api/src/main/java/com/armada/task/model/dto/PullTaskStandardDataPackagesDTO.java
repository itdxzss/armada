package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskCreationMode;
import java.util.List;

/** 数据包选料请求；每个包的未使用号码冻结成一个逻辑执行单元。
 * @param creationMode 任务创建模式
 * @param packageIds 本次选中的数据包，保持选择顺序
 * @param groupFolderId 粘贴链接模式可选的群分组
 * @param linksText 粘贴链接模式的完整候选链接文本
 */
public record PullTaskStandardDataPackagesDTO(PullTaskCreationMode creationMode,
        List<Long> packageIds, Long groupFolderId, String linksText) { }
