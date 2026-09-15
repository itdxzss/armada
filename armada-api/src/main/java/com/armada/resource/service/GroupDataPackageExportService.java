package com.armada.resource.service;

import com.armada.resource.model.vo.GroupDataPackageExportVO;
import java.util.List;

/** 真实状态号码导出，跨包总量有明确上限。 */
public interface GroupDataPackageExportService {
    /** 单包及批量共用同一过滤及文件格式。 */
    GroupDataPackageExportVO export(List<Long> ids, String usageStatus, String format);
}
